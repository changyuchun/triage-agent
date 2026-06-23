package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.TaskExecutionEngine;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 执行节点：并行执行计划中的工具调用和知识检索，收集证据（Evidence）。
 * <p>
 * 内部实现（TaskExecutionEngine）：
 * - KNOWLEDGE_RETRIEVE：顺序执行
 * - TOOL_CALL：Parallel Tool Calling（CompletableFuture.allOf 并行）
 * <p>
 * 输出 State：{@link AgentStateKeys#EVIDENCE}、{@link AgentStateKeys#QUALITY_SCORE}
 * 质量分数决定后续走 reretrieve（不足时）还是 synthesize（足够时）。
 */
@Component
public class ExecuteNode {

    private final TaskExecutionEngine engine;
    private final MemoryStore memStore;
    private final AgentProperties properties;

    public ExecuteNode(TaskExecutionEngine engine,
                       MemoryStore memStore,
                       AgentProperties properties) {
        this.engine = engine;
        this.memStore = memStore;
        this.properties = properties;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        SlotState slots = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route = getOrDefault(state, AgentStateKeys.ROUTE, null);
        ExecutionPlan plan = getOrDefault(state, AgentStateKeys.PLAN, null);
        int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);

        EvidencePackage evidence = engine.execute(plan, slots, route);
        memStore.load(sessionId).evidencePackage(evidence);

        boolean lowQuality = evidence.qualityScore() < properties.runtime().minEvidenceScore()
                && retryCount < 1;
        String nextNode = lowQuality ? "reretrieve" : "synthesize";
        return Map.of(
                AgentStateKeys.EVIDENCE, evidence,
                AgentStateKeys.QUALITY_SCORE, evidence.qualityScore(),
                AgentStateKeys.NEXT_NODE, nextNode,
                AgentStateKeys.AGENT_STATE,
                        lowQuality ? AgentState.RERETRIEVE.name() : AgentState.SYNTHESIZE.name(),
                AgentStateKeys.TRACE,
                        "execute→" + nextNode + ": quality=" + evidence.qualityScore() + " retry=" + retryCount);
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
