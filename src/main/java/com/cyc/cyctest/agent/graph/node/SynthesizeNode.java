package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.AnswerSynthesizer;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 答案合成节点（Graph 末节点）。
 * <p>
 * 职责：
 * 1. 从 VectorStore 召回语义相似的历史情节记忆（L4 Episodic Memory）
 * 2. 调用 {@link AnswerSynthesizer} 合成最终答案（LLM + Template 双模式）
 * 3. 更新对话上下文（recordRoute、recordEvidence、addTurn(assistant)）
 * 4. 触发 L3 摘要压缩（异步可选）
 * 5. 记录本轮情节到 VectorStore（recordEpisode）
 * 6. 持久化 Memory 到 Redis
 */
@Component
public class SynthesizeNode {

    private final AnswerSynthesizer synthesizer;
    private final MemoryStore memStore;
    private final EpisodicMemoryService episodic;
    private final MemoryCompressionService compression;

    public SynthesizeNode(AnswerSynthesizer synthesizer,
                          MemoryStore memStore,
                          EpisodicMemoryService episodic,
                          MemoryCompressionService compression) {
        this.synthesizer = synthesizer;
        this.memStore = memStore;
        this.episodic = episodic;
        this.compression = compression;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route = getOrDefault(state, AgentStateKeys.ROUTE, null);
        ExecutionPlan plan = getOrDefault(state, AgentStateKeys.PLAN, null);
        EvidencePackage evidence = state.value(AgentStateKeys.EVIDENCE, EvidencePackage.empty());
        ClarifyLlmResult clarify = getOrDefault(state, AgentStateKeys.CLARIFY,
                ClarifyLlmResult.fallback(userText, slots));
        int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);

        ConversationContext memory = memStore.load(sessionId);
        memory.resetClarifyCount();
        memory.recordRoute(route);
        memory.recordEvidence(evidence.evidence());

        // L4：情节记忆召回，为 LLM 提供历史相似问题的处理经验
        List<String> episodicContext = episodic.recallRelevant(userText, 3);

        AgentRunContext ctx = new AgentRunContext(
                sessionId, userText, slots, clarify, route, plan, evidence,
                AgentState.SYNTHESIZE, retryCount, null, null, List.of());
        String answer = synthesizer.synthesize(ctx, episodicContext);

        memory.addTurn("assistant", answer);
        // L3：记忆压缩（turn 数超阈值时触发 LLM 摘要）
        compression.compressIfNeeded(memory);
        // L4：记录本轮情节到 VectorStore
        episodic.recordEpisode(sessionId, ctx.withState(AgentState.DONE, "synthesized"));
        memStore.save(memory);

        return Map.of(
                AgentStateKeys.ANSWER, answer,
                AgentStateKeys.WAITING, false,
                AgentStateKeys.AGENT_STATE, AgentState.DONE.name(),
                AgentStateKeys.TRACE,
                        "synthesize: done, episodic_recalled=" + episodicContext.size());
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
