package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.cache.SemanticCacheService;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.AgentRunContext;
import com.cyc.cyctest.agent.core.AgentModels.AgentState;
import com.cyc.cyctest.agent.core.AgentModels.ChatResponse;
import com.cyc.cyctest.agent.core.AgentModels.ClarifyDecision;
import com.cyc.cyctest.agent.core.AgentModels.ClarifyLlmResult;
import com.cyc.cyctest.agent.core.AgentModels.EvidencePackage;
import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.EpisodicMemoryService;
import com.cyc.cyctest.agent.memory.MemoryCompressionService;
import com.cyc.cyctest.agent.memory.MemoryStore;
import com.cyc.cyctest.agent.slot.SlotExtractionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行时（经典 while-switch 状态机实现）。
 * <p>
 * 保留原始实现供对比；生产推荐使用 {@link GraphAgentRuntime}（StateGraph 版本）。
 * <p>
 * 执行流程：
 * <pre>
 *   EXTRACT → CLARIFY（可选）→ ROUTE → PLAN → EXECUTE
 *           ↓                                        ↓
 *   WAITING_USER_INPUT                    RERETRIEVE（质量不足时）
 *                                                    ↓
 *                                              SYNTHESIZE → DONE
 * </pre>
 */
@Service
public class AgentRuntime implements IAgentRuntime {

    private final MemoryStore memoryStore;
    private final SlotExtractionService slotExtractionService;
    private final ClarifyService clarifyService;
    private final DomainRouter domainRouter;
    private final TaskPlanner taskPlanner;
    private final TaskExecutionEngine executionEngine;
    private final AnswerSynthesizer answerSynthesizer;
    private final AgentProperties properties;
    private final MemoryCompressionService memoryCompressionService;
    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticCacheService semanticCacheService;

    public AgentRuntime(MemoryStore memoryStore,
                        SlotExtractionService slotExtractionService,
                        ClarifyService clarifyService,
                        DomainRouter domainRouter,
                        TaskPlanner taskPlanner,
                        TaskExecutionEngine executionEngine,
                        AnswerSynthesizer answerSynthesizer,
                        AgentProperties properties,
                        MemoryCompressionService memoryCompressionService,
                        EpisodicMemoryService episodicMemoryService,
                        SemanticCacheService semanticCacheService) {
        this.memoryStore = memoryStore;
        this.slotExtractionService = slotExtractionService;
        this.clarifyService = clarifyService;
        this.domainRouter = domainRouter;
        this.taskPlanner = taskPlanner;
        this.executionEngine = executionEngine;
        this.answerSynthesizer = answerSynthesizer;
        this.properties = properties;
        this.memoryCompressionService = memoryCompressionService;
        this.episodicMemoryService = episodicMemoryService;
        this.semanticCacheService = semanticCacheService;
    }

    @Override
    public ChatResponse run(String sessionId, String userText) {
        // Semantic Cache（L0）：相似问题直接命中缓存，跳过完整 Agent 循环，降低 LLM 调用成本
        Optional<String> cached = semanticCacheService.get(userText);
        if (cached.isPresent()) {
            ConversationContext mem = memoryStore.load(sessionId);
            mem.addTurn("user", userText);
            mem.addTurn("assistant", "[CACHE] " + cached.get());
            memoryStore.save(mem);
            return new ChatResponse(sessionId, AgentState.DONE.name(), false, null, null,
                    cached.get(), null, null, List.of(), List.of("semantic_cache_hit"));
        }

        ConversationContext memory = memoryStore.load(sessionId);
        memory.addTurn("user", userText);

        AgentRunContext ctx = new AgentRunContext(
                memory.sessionId(), userText, memory.slotState(), null, null, null, EvidencePackage.empty(),
                AgentState.EXTRACT, 0, null, memory.pendingClarifyQuestion(), List.of("start")
        );

        int guard = 0;
        while (ctx.state() != AgentState.DONE && ctx.state() != AgentState.WAITING_USER_INPUT
                && ctx.state() != AgentState.FAILED && guard++ < 20) {
            ctx = switch (ctx.state()) {
                case EXTRACT -> extract(ctx, memory);
                case CLARIFY -> clarify(ctx, memory);
                case ROUTE -> route(ctx, memory);
                case PLAN -> plan(ctx, memory);
                case EXECUTE -> execute(ctx, memory);
                case RERETRIEVE -> reRetrieve(ctx);
                case SYNTHESIZE -> synthesize(ctx, memory);
                default -> ctx.withState(AgentState.FAILED, "unexpected state");
            };
        }

        if (guard >= 20) {
            ctx = ctx.withState(AgentState.FAILED, "runtime guard exceeded");
        }
        if (ctx.finalAnswer() != null) {
            memory.addTurn("assistant", ctx.finalAnswer());
        }

        // L3 记忆压缩：使用 LLM 将旧轮次压缩为摘要
        memoryCompressionService.compressIfNeeded(memory);

        // L4 情节记忆：将本次完整对话结果记录到 VectorStore
        if (ctx.state() == AgentState.DONE) {
            episodicMemoryService.recordEpisode(memory.sessionId(), ctx);
        }

        // 持久化到 Redis（生产模式）
        memoryStore.save(memory);

        // Semantic Cache 写入：将 DONE 状态的答案写入语义缓存，供后续相似问题命中
        if (ctx.state() == AgentState.DONE && ctx.finalAnswer() != null) {
            semanticCacheService.put(userText, ctx.finalAnswer());
        }

        boolean waiting = ctx.state() == AgentState.WAITING_USER_INPUT;
        return new ChatResponse(ctx.sessionId(), ctx.state().name(), waiting, ctx.clarifyQuestion(),
                waiting ? "请使用相同 sessionId 继续补充信息，下一轮会继承已提取槽位并从 EXTRACT 重新进入。" : null,
                ctx.finalAnswer(), ctx.slots(), ctx.route(),
                ctx.evidence().evidence(), ctx.trace());
    }

    private AgentRunContext extract(AgentRunContext ctx, ConversationContext memory) {
        SlotState slots = slotExtractionService.extractAndMerge(ctx.userText(), memory);
        ClarifyLlmResult clarify = clarifyService.analyze(ctx.userText(), memory, slots);
        memory.currentGoal(clarify.userGoal());
        ClarifyDecision decision = clarifyService.decide(clarify, slots, memory);
        AgentState next = decision.needAsk() ? AgentState.CLARIFY : AgentState.ROUTE;
        String answer = decision.needAsk() ? decision.question() : ctx.finalAnswer();
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), slots, clarify, ctx.route(), ctx.plan(),
                ctx.evidence(), next, ctx.retryCount(), answer, decision.needAsk() ? decision.question() : ctx.clarifyQuestion(),
                ctx.trace())
                .withState(next, decision.reason());
    }

    private AgentRunContext clarify(AgentRunContext ctx, ConversationContext memory) {
        memory.increaseClarifyCount();
        memory.pendingClarifyQuestion(ctx.clarifyQuestion());
        return ctx.withState(AgentState.WAITING_USER_INPUT, "wait user clarification");
    }

    private AgentRunContext route(AgentRunContext ctx, ConversationContext memory) {
        RouteResult route = domainRouter.route(ctx.userText(), ctx.clarify(), ctx.slots());
        memory.currentRoute(route);
        memoryStore.save(memory);
        if ("clarify_required".equals(route.handleMode())) {
            // 澄清次数已达上限时强制放行，避免 ROUTE→CLARIFY→EXTRACT→ROUTE 死循环
            if (memory.clarifyCount() >= properties.runtime().maxClarifyRounds()) {
                RouteResult forced = new RouteResult(
                        route.domainCode(), route.domainName(),
                        route.subDomainCode(), route.subDomainName(),
                        "knowledge_and_tool", route.confidence(),
                        "forced: max clarify rounds reached");
                return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), forced, ctx.plan(),
                        ctx.evidence(), AgentState.PLAN, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                        .withState(AgentState.PLAN, "forced route after max clarify");
            }
            String answer = "这个问题可能涉及多个领域，请确认你要排查的是支付、交易、履约还是营销？";
            return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), route, ctx.plan(),
                    ctx.evidence(), AgentState.CLARIFY, ctx.retryCount(), answer, answer, ctx.trace())
                    .withState(AgentState.CLARIFY, "route low confidence");
        }
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), route, ctx.plan(),
                ctx.evidence(), AgentState.PLAN, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(AgentState.PLAN, route.reason());
    }

    private AgentRunContext plan(AgentRunContext ctx, ConversationContext memory) {
        ExecutionPlan plan = taskPlanner.plan(ctx.userText(), ctx.slots(), ctx.route());
        memory.currentPlan(plan);
        memoryStore.save(memory);
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), plan,
                ctx.evidence(), AgentState.EXECUTE, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(AgentState.EXECUTE, "steps=" + plan.steps().size());
    }

    private AgentRunContext execute(AgentRunContext ctx, ConversationContext memory) {
        EvidencePackage evidence = executionEngine.execute(ctx.plan(), ctx.slots(), ctx.route());
        memory.evidencePackage(evidence);
        memoryStore.save(memory);
        AgentState next = evidence.qualityScore() < properties.runtime().minEvidenceScore() && ctx.retryCount() < 1
                ? AgentState.RERETRIEVE : AgentState.SYNTHESIZE;
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), ctx.plan(),
                evidence, next, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(next, "evidenceQuality=" + evidence.qualityScore());
    }

    private AgentRunContext reRetrieve(AgentRunContext ctx) {
        ExecutionPlan rewritten = taskPlanner.rewriteRetrievalPlan(ctx.plan(), ctx.userText(), ctx.slots().errorCode());
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), rewritten,
                ctx.evidence(), AgentState.EXECUTE, ctx.retryCount() + 1, ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(AgentState.EXECUTE, "rewrite retrieval query");
    }

    private AgentRunContext synthesize(AgentRunContext ctx, ConversationContext memory) {
        memory.resetClarifyCount();
        memory.recordRoute(ctx.route());
        memory.recordEvidence(ctx.evidence().evidence());

        // L4 情景记忆召回：检索与当前问题语义相似的历史处理经验
        List<String> episodicContext = episodicMemoryService.recallRelevant(ctx.userText(), 3);

        String answer = answerSynthesizer.synthesize(ctx, episodicContext);
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), ctx.plan(),
                ctx.evidence(), AgentState.DONE, ctx.retryCount(), answer, null, ctx.trace())
                .withState(AgentState.DONE, "answer synthesized, episodic_recalled=" + episodicContext.size());
    }
}
