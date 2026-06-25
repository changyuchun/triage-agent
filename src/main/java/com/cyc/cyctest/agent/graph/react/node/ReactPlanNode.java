package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("reactPlanNode")
public class ReactPlanNode extends ReactAgentNode {

    private final SkillRegistry skillRegistry;

    public ReactPlanNode(ChatModel chatModel, JsonSupport jsonSupport, SkillRegistry skillRegistry) {
        super(chatModel, jsonSupport);
        this.skillRegistry = skillRegistry;
    }

    @Override
    protected String buildSystemPrompt() {
        return """
                你是任务规划专家。根据路由结果、可用工具和诊断 SOP，制定执行步骤列表。
                只输出 JSON，格式如下：
                {"steps":[
                  {"stepId":"knowledge_1","type":"KNOWLEDGE_RETRIEVE","query":"...","dependsOn":[],"required":false},
                  {"stepId":"payment_query_step","type":"TOOL_CALL","toolCode":"payment_query",
                   "args":{"payOrderId":"${slots.payOrderId}","env":"${slots.env}"},
                   "dependsOn":[],"required":true},
                  {"stepId":"log_query_step","type":"TOOL_CALL","toolCode":"log_query",
                   "args":{"keyword":"${payment_query_step.errorCode}","timeRange":"1h"},
                   "dependsOn":["payment_query_step"],"required":false,
                   "condition":"${payment_query_step.status} == FAILED"}
                ]}
                规则：type 只能是 TOOL_CALL 或 KNOWLEDGE_RETRIEVE；args 用 ${slots.field} 引用槽位，
                用 ${stepId.field} 引用前置步骤结果；condition 不满足时跳过该步骤。
                """;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        RouteResult route = get(state, AgentStateKeys.ROUTE, null);
        SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
        String userText = state.value(AgentStateKeys.USER_TEXT, "");

        StringBuilder sb = new StringBuilder();
        sb.append("用户目标：").append(userText).append("\n");
        if (route != null) {
            sb.append("路由：domain=").append(route.domainCode())
              .append(" subDomain=").append(route.subDomainCode())
              .append(" handleMode=").append(route.handleMode()).append("\n");

            skillRegistry.findByDomain(route.domainCode(), route.subDomainCode()).forEach(meta -> {
                if (!meta.toolFlow().isEmpty()) {
                    sb.append("参考工具流程（").append(meta.name()).append("）：\n");
                    sb.append(meta.toolFlowSummary());
                }
                if (!meta.sopSummary().isBlank()) {
                    sb.append("诊断 SOP 摘要（据此生成条件分支）：\n");
                    sb.append(meta.sopSummary()).append("\n");
                }
            });
        }
        sb.append("槽位：").append(jsonSupport.write(slots));
        return sb.toString();
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        try {
            ExecutionPlan plan = jsonSupport.readJsonObject(response, ExecutionPlan.class);
            List<PlanStep> steps = plan != null ? plan.steps() : List.of();
            return Map.of(
                    AgentStateKeys.PLAN, plan != null ? plan : new ExecutionPlan(List.of()),
                    AgentStateKeys.AGENT_STATE, AgentState.PLAN.name(),
                    AgentStateKeys.TRACE, "react-plan: steps=" + steps.size()
            );
        } catch (Exception e) {
            log.warn("ReactPlanNode parse failed: {}", e.getMessage());
            return Map.of(
                    AgentStateKeys.PLAN, new ExecutionPlan(List.of()),
                    AgentStateKeys.AGENT_STATE, AgentState.PLAN.name(),
                    AgentStateKeys.TRACE, "react-plan parse-error: " + e.getMessage()
            );
        }
    }
}
