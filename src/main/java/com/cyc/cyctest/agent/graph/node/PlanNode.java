package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.TaskPlanner;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 任务规划节点：将路由结果转化为具体的执行步骤列表（ExecutionPlan）。
 * <p>
 * 规划逻辑（LLM + 规则双模式）：
 * - LLM 可用时：让模型结合领域、槽位、工具列表生成步骤
 * - LLM 不可用时：基于 handleMode 走规则模板
 * <p>
 * ExecutionPlan 中的每个 PlanStep 包含：stepId、type（KNOWLEDGE_RETRIEVE/TOOL_CALL）、
 * toolCode、query、dependsOn、required。
 */
@Component
public class PlanNode {

    private final TaskPlanner taskPlanner;
    private final MemoryStore memStore;

    public PlanNode(TaskPlanner taskPlanner, MemoryStore memStore) {
        this.taskPlanner = taskPlanner;
        this.memStore = memStore;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route = getOrDefault(state, AgentStateKeys.ROUTE, null);

        ExecutionPlan plan = taskPlanner.plan(userText, slots, route);

        ConversationContext memory = memStore.load(sessionId);
        memory.currentPlan(plan);
        memStore.save(memory);

        return Map.of(
                AgentStateKeys.PLAN, plan,
                AgentStateKeys.TRACE, "plan: steps=" + plan.steps().size());
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
