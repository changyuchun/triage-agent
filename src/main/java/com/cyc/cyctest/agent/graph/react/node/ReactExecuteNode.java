package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.TaskExecutionEngine;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具执行节点（ReactAgent 版）。
 * 从 state 读取 ReactPlanNode 生成的 ExecutionPlan，交由 TaskExecutionEngine 执行：
 * DAG 拓扑排序 → 同层并行工具调用 → 变量替换 → 条件判断 → 结构化 Evidence 收集。
 * 与经典版 ExecuteNode 复用同一执行引擎，保证行为一致。
 */
@Component("reactExecuteNode")
public class ReactExecuteNode extends ReactAgentNode {

    private final TaskExecutionEngine engine;
    private final AgentProperties agentProperties;

    public ReactExecuteNode(ChatModel chatModel, JsonSupport jsonSupport,
                            TaskExecutionEngine engine,
                            AgentProperties agentProperties) {
        super(chatModel, jsonSupport);
        this.engine = engine;
        this.agentProperties = agentProperties;
    }

    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
            RouteResult route = get(state, AgentStateKeys.ROUTE, null);
            ExecutionPlan plan = get(state, AgentStateKeys.PLAN, null);
            int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);
            String userText = state.value(AgentStateKeys.USER_TEXT, "");

            if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
                plan = new ExecutionPlan(List.of(new PlanStep(
                        "knowledge_fallback", StepType.KNOWLEDGE_RETRIEVE,
                        null, userText, List.of(), false)));
            }

            try {
                EvidencePackage evidence = engine.execute(plan, slots, route);

                boolean lowQuality = evidence.qualityScore() < agentProperties.runtime().minEvidenceScore()
                        && retryCount < 1;
                String nextNode = lowQuality ? "reretrieve" : "synthesize";

                return Map.of(
                        AgentStateKeys.EVIDENCE, evidence,
                        AgentStateKeys.QUALITY_SCORE, evidence.qualityScore(),
                        AgentStateKeys.NEXT_NODE, nextNode,
                        AgentStateKeys.AGENT_STATE,
                                lowQuality ? AgentState.RERETRIEVE.name() : AgentState.SYNTHESIZE.name(),
                        AgentStateKeys.TRACE,
                                "react-execute→" + nextNode + " evidence=" + evidence.evidence().size()
                                        + " quality=" + evidence.qualityScore()
                );
            } catch (Exception e) {
                log.error("ReactExecuteNode failed: {}", e.getMessage(), e);
                Evidence ev = new Evidence("react-execute-error", "tool_error", "执行失败",
                        e.getMessage(), 0.0, Map.of());
                return Map.of(
                        AgentStateKeys.EVIDENCE, new EvidencePackage(List.of(ev), 0.0),
                        AgentStateKeys.QUALITY_SCORE, 0.0,
                        AgentStateKeys.NEXT_NODE, retryCount < 1 ? "reretrieve" : "synthesize",
                        AgentStateKeys.AGENT_STATE, AgentState.FAILED.name(),
                        AgentStateKeys.TRACE, "react-execute error: " + e.getMessage()
                );
            }
        });
    }

    @Override
    protected String buildSystemPrompt() { return ""; }

    @Override
    protected String buildUserPrompt(OverAllState state) { return ""; }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) { return Map.of(); }
}
