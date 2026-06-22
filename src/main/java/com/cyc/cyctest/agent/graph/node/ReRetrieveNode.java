package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.TaskPlanner;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 重检索节点：当证据质量不足时，重写检索查询并重新执行（最多 1 次）。
 * <p>
 * 触发条件：qualityScore &lt; minEvidenceScore 且 retryCount &lt; 1。
 * <p>
 * 重写策略（TaskPlanner.rewriteRetrievalPlan）：
 * 结合 errorCode 槽位扩展查询关键词，提升检索覆盖率。
 */
@Component
public class ReRetrieveNode {

    private final TaskPlanner taskPlanner;

    public ReRetrieveNode(TaskPlanner taskPlanner) {
        this.taskPlanner = taskPlanner;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        ExecutionPlan plan = getOrDefault(state, AgentStateKeys.PLAN, null);
        int retry = state.value(AgentStateKeys.RETRY_COUNT, 0);

        ExecutionPlan rewritten = taskPlanner.rewriteRetrievalPlan(plan, userText, slots.errorCode());

        return Map.of(
                AgentStateKeys.PLAN, rewritten,
                AgentStateKeys.RETRY_COUNT, retry + 1,
                AgentStateKeys.AGENT_STATE, AgentState.EXECUTE.name(),
                AgentStateKeys.TRACE, "reretrieve: retry=" + (retry + 1));
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
