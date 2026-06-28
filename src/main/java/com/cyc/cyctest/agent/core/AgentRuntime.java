package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.cache.SemanticCacheService;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.AgentProgressEvent;
import com.cyc.cyctest.agent.core.AgentModels.AgentRunContext;
import com.cyc.cyctest.agent.core.AgentModels.ProgressCallback;
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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * Agent 运行时（经典 while-switch 状态机实现）。
 * <p>
 * 保留原始实现供对比；生产推荐使用 {@link }（StateGraph 版本）。
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
        // L0 语义缓存：key=userText（确定性输入，稳定 embedding），仅 knowledge_only 答案会被写入
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

        if (ctx.state() == AgentState.DONE && ctx.finalAnswer() != null) {
            putCacheIfEligible(ctx, ctx.finalAnswer());
        }

        boolean waiting = ctx.state() == AgentState.WAITING_USER_INPUT;
        return new ChatResponse(ctx.sessionId(), ctx.state().name(), waiting, ctx.clarifyQuestion(),
                waiting ? "请使用相同 sessionId 继续补充信息，下一轮会继承已提取槽位并从 EXTRACT 重新进入。" : null,
                ctx.finalAnswer(), ctx.slots(), ctx.route(),
                ctx.evidence().evidence(), ctx.trace());
    }

    /**
     * 带进度回调的同步执行。
     * <p>
     * 与 run() 的唯一区别：在进入每个阶段前触发 callback.onProgress()，
     * ROUTE / EXECUTE 完成后额外触发一次带结果摘要的补充事件。
     * 适合 SSE 端点：CompletableFuture 跑 pipeline，callback 将事件推入 SseEmitter。
     */
    @Override
    public ChatResponse runWithCallback(String sessionId, String userText, ProgressCallback callback) {
        Optional<String> cached = semanticCacheService.get(userText);
        if (cached.isPresent()) {
            ConversationContext mem = memoryStore.load(sessionId);
            mem.addTurn("user", userText);
            mem.addTurn("assistant", "[CACHE] " + cached.get());
            memoryStore.save(mem);
            callback.onProgress("cache_hit", "命中语义缓存，直接返回答案");
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

            callback.onProgress(stageType(ctx.state()), stageMsg(ctx.state()));
            AgentState prevState = ctx.state();

            ctx = switch (ctx.state()) {
                case EXTRACT    -> extract(ctx, memory);
                case CLARIFY    -> clarify(ctx, memory);
                case ROUTE      -> route(ctx, memory);
                case PLAN       -> plan(ctx, memory);
                case EXECUTE    -> execute(ctx, memory);
                case RERETRIEVE -> reRetrieve(ctx);
                case SYNTHESIZE -> {
                    memory.resetClarifyCount();
                    memory.recordRoute(ctx.route());
                    memory.recordEvidence(ctx.evidence().evidence());
                    List<String> episodic = episodicMemoryService.recallRelevant(
                            EpisodicMemoryService.effectiveGoal(ctx), 3);
                    StringBuilder sb = new StringBuilder();
                    for (String token : answerSynthesizer.synthesizeStream(ctx, episodic).toIterable()) {
                        sb.append(token);
                        callback.onToken(token);
                    }
                    yield ctx.withFinalAnswer(sb.toString());
                }
                default         -> ctx.withState(AgentState.FAILED, "unexpected state");
            };

            if (prevState == AgentState.ROUTE && ctx.route() != null) {
                callback.onProgress("route_done",
                        "已路由至 " + ctx.route().domainCode() + "/" + ctx.route().subDomainCode()
                        + "，置信度 " + String.format("%.2f", ctx.route().confidence()));
            }
            if (prevState == AgentState.EXECUTE) {
                callback.onProgress("execute_done",
                        "工具执行完成，证据质量分 " + String.format("%.2f", ctx.evidence().qualityScore())
                        + "，共 " + ctx.evidence().evidence().size() + " 条证据");
            }
        }

        if (guard >= 20) ctx = ctx.withState(AgentState.FAILED, "runtime guard exceeded");
        if (ctx.finalAnswer() != null) memory.addTurn("assistant", ctx.finalAnswer());
        memoryCompressionService.compressIfNeeded(memory);
        if (ctx.state() == AgentState.DONE) episodicMemoryService.recordEpisode(memory.sessionId(), ctx);
        memoryStore.save(memory);
        if (ctx.state() == AgentState.DONE && ctx.finalAnswer() != null) {
            putCacheIfEligible(ctx, ctx.finalAnswer());
        }

        boolean waiting = ctx.state() == AgentState.WAITING_USER_INPUT;
        return new ChatResponse(ctx.sessionId(), ctx.state().name(), waiting, ctx.clarifyQuestion(),
                waiting ? "请使用相同 sessionId 继续补充信息，下一轮会继承已提取槽位并从 EXTRACT 重新进入。" : null,
                ctx.finalAnswer(), ctx.slots(), ctx.route(),
                ctx.evidence().evidence(), ctx.trace());
    }

    /**
     * 流式执行：各阶段实时推送 Progress 事件，SYNTHESIZE 阶段 token 级推送。
     * 事件顺序：Progress(extract)→…→Progress(synthesizing)→Token×N→Done。
     */
    @Override
    public Flux<AgentProgressEvent> stream(String sessionId, String userText) {
        return Flux.<AgentProgressEvent>create(sink -> {
            // L0 语义缓存
            Optional<String> cached = semanticCacheService.get(userText);
            if (cached.isPresent()) {
                ConversationContext mem = memoryStore.load(sessionId);
                mem.addTurn("user", userText);
                mem.addTurn("assistant", "[CACHE] " + cached.get());
                memoryStore.save(mem);
                ChatResponse response = new ChatResponse(sessionId, AgentState.DONE.name(), false,
                        null, null, cached.get(), null, null, List.of(), List.of("semantic_cache_hit"));
                sink.next(new AgentProgressEvent.Done(response));
                sink.complete();
                return;
            }

            ConversationContext memory = memoryStore.load(sessionId);
            memory.addTurn("user", userText);

            AgentRunContext ctx = new AgentRunContext(
                    memory.sessionId(), userText, memory.slotState(), null, null, null,
                    EvidencePackage.empty(), AgentState.EXTRACT, 0, null,
                    memory.pendingClarifyQuestion(), List.of("start")
            );

            // 运行各阶段（SYNTHESIZE 之前），实时推送进度事件
            int guard = 0;
            while (ctx.state() != AgentState.DONE
                    && ctx.state() != AgentState.WAITING_USER_INPUT
                    && ctx.state() != AgentState.FAILED
                    && ctx.state() != AgentState.SYNTHESIZE
                    && guard++ < 20) {
                sink.next(new AgentProgressEvent.Progress(stageType(ctx.state()), stageMsg(ctx.state())));
                ctx = switch (ctx.state()) {
                    case EXTRACT     -> extract(ctx, memory);
                    case CLARIFY     -> clarify(ctx, memory);
                    case ROUTE       -> route(ctx, memory);
                    case PLAN        -> plan(ctx, memory);
                    case EXECUTE     -> execute(ctx, memory);
                    case RERETRIEVE  -> reRetrieve(ctx);
                    default          -> ctx.withState(AgentState.FAILED, "unexpected state in stream");
                };
            }

            if (ctx.state() == AgentState.SYNTHESIZE) {
                // SYNTHESIZE：token 流式推送
                memory.resetClarifyCount();
                memory.recordRoute(ctx.route());
                memory.recordEvidence(ctx.evidence().evidence());
                List<String> episodic = episodicMemoryService.recallRelevant(
                        EpisodicMemoryService.effectiveGoal(ctx), 3);
                sink.next(new AgentProgressEvent.Progress("synthesizing", "正在生成答案..."));

                final AgentRunContext finalCtx = ctx;
                StringBuilder fullAnswer = new StringBuilder();
                answerSynthesizer.synthesizeStream(finalCtx, episodic)
                        .subscribe(
                                token -> {
                                    fullAnswer.append(token);
                                    sink.next(new AgentProgressEvent.Token(token));
                                },
                                sink::error,
                                () -> {
                                    String answer = fullAnswer.toString();
                                    memory.addTurn("assistant", answer);
                                    memoryCompressionService.compressIfNeeded(memory);
                                    episodicMemoryService.recordEpisode(
                                            memory.sessionId(), finalCtx.withFinalAnswer(answer));
                                    memoryStore.save(memory);
                                    putCacheIfEligible(finalCtx.withFinalAnswer(answer), answer);
                                    ChatResponse response = new ChatResponse(
                                            sessionId, AgentState.DONE.name(), false, null, null,
                                            answer, finalCtx.slots(), finalCtx.route(),
                                            finalCtx.evidence().evidence(), finalCtx.trace());
                                    sink.next(new AgentProgressEvent.Done(response));
                                    sink.complete();
                                }
                        );
            } else {
                // WAITING / FAILED / DONE（无 synthesize，如澄清中断）
                if (ctx.finalAnswer() != null) memory.addTurn("assistant", ctx.finalAnswer());
                memoryCompressionService.compressIfNeeded(memory);
                if (ctx.state() == AgentState.DONE) {
                    episodicMemoryService.recordEpisode(memory.sessionId(), ctx);
                }
                memoryStore.save(memory);
                if (ctx.state() == AgentState.DONE && ctx.finalAnswer() != null) {
                    putCacheIfEligible(ctx, ctx.finalAnswer());
                }
                boolean waiting = ctx.state() == AgentState.WAITING_USER_INPUT;
                ChatResponse response = new ChatResponse(
                        ctx.sessionId(), ctx.state().name(), waiting, ctx.clarifyQuestion(),
                        waiting ? "请使用相同 sessionId 继续补充信息，下一轮会继承已提取槽位并从 EXTRACT 重新进入。" : null,
                        ctx.finalAnswer(), ctx.slots(), ctx.route(),
                        ctx.evidence().evidence(), ctx.trace());
                sink.next(new AgentProgressEvent.Done(response));
                sink.complete();
            }
        }).subscribeOn(Schedulers.boundedElastic());  // 阻塞的 pipeline 阶段跑在弹性线程池，不占用 HTTP 线程
    }

    /**
     * 语义缓存写入：只缓存 knowledge_only 答案，用原始 userText 作 key。
     * userText 确定性输入→确定性 embedding，hit 率高于 LLM 生成的 effectiveGoal（非确定）。
     * 排除 tool_call / knowledge_and_tool 答案（含 slot 相关数据，跨会话命中必然错误）。
     */
    private void putCacheIfEligible(AgentRunContext ctx, String answer) {
        if (ctx.route() != null && "knowledge_only".equals(ctx.route().handleMode())) {
            semanticCacheService.put(ctx.userText(), answer);
        }
    }

    private static String stageType(AgentState state) {
        return state.name().toLowerCase();
    }

    private static String stageMsg(AgentState state) {
        return switch (state) {
            case EXTRACT    -> "正在理解您的问题...";
            case CLARIFY    -> "正在分析澄清需求...";
            case ROUTE      -> "正在路由到对应领域...";
            case PLAN       -> "正在规划执行步骤...";
            case EXECUTE    -> "正在执行工具调用...";
            case RERETRIEVE -> "证据质量不足，正在补充检索...";
            default         -> state.name();
        };
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
        RouteResult previousRoute = memory.currentRoute();   // 记录旧领域，用于检测切换
        RouteResult route = domainRouter.route(ctx.userText(), ctx.clarify(), ctx.slots());

        // 领域切换检测：用户话题跨领域时（如从交易转到支付），重置旧领域专属 slot，
        // 避免 orderId 污染支付查询、payOrderId 污染交易查询。
        // env/errorCode/timeRange 属于跨领域信息，始终保留。
        SlotState slots = ctx.slots();
        if (isDomainSwitch(previousRoute, route)) {
            slots = slots.resetForDomain(route.domainCode());
            memory.setSlots(slots);   // 直接替换，不走 merge，防止旧值通过 first(newer,older) 回填
        }

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
                return new AgentRunContext(ctx.sessionId(), ctx.userText(), slots, ctx.clarify(), forced, ctx.plan(),
                        ctx.evidence(), AgentState.PLAN, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                        .withState(AgentState.PLAN, "forced route after max clarify");
            }
            String answer = "这个问题可能涉及多个领域，请确认你要排查的是支付、交易、履约还是营销？";
            return new AgentRunContext(ctx.sessionId(), ctx.userText(), slots, ctx.clarify(), route, ctx.plan(),
                    ctx.evidence(), AgentState.CLARIFY, ctx.retryCount(), answer, answer, ctx.trace())
                    .withState(AgentState.CLARIFY, "route low confidence");
        }
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), slots, ctx.clarify(), route, ctx.plan(),
                ctx.evidence(), AgentState.PLAN, ctx.retryCount(), ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(AgentState.PLAN, route.reason());
    }

    private static boolean isDomainSwitch(RouteResult prev, RouteResult next) {
        if (prev == null || next == null) return false;
        String prevDomain = prev.domainCode();
        String nextDomain = next.domainCode();
        return prevDomain != null && nextDomain != null && !prevDomain.equals(nextDomain);
    }

    private AgentRunContext plan(AgentRunContext ctx, ConversationContext memory) {
        ExecutionPlan plan = taskPlanner.plan(EpisodicMemoryService.effectiveGoal(ctx), ctx.slots(), ctx.route());
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
        ExecutionPlan rewritten = taskPlanner.rewriteRetrievalPlan(
                ctx.plan(), EpisodicMemoryService.effectiveGoal(ctx), ctx.slots().errorCode());
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), rewritten,
                ctx.evidence(), AgentState.EXECUTE, ctx.retryCount() + 1, ctx.finalAnswer(), ctx.clarifyQuestion(), ctx.trace())
                .withState(AgentState.EXECUTE, "rewrite retrieval query");
    }

    private AgentRunContext synthesize(AgentRunContext ctx, ConversationContext memory) {
        memory.resetClarifyCount();
        memory.recordRoute(ctx.route());
        memory.recordEvidence(ctx.evidence().evidence());

        // L4 情景记忆召回：用 effectiveGoal（含上下文推断）而非裸 userText 查询，
        // 保证写入向量时的 goal 和召回时的 query 在同一语义空间
        List<String> episodicContext = episodicMemoryService.recallRelevant(
                EpisodicMemoryService.effectiveGoal(ctx), 3);

        String answer = answerSynthesizer.synthesize(ctx, episodicContext);
        return new AgentRunContext(ctx.sessionId(), ctx.userText(), ctx.slots(), ctx.clarify(), ctx.route(), ctx.plan(),
                ctx.evidence(), AgentState.DONE, ctx.retryCount(), answer, null, ctx.trace())
                .withState(AgentState.DONE, "answer synthesized, episodic_recalled=" + episodicContext.size());
    }
}
