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
                  {"stepId":"payment_query","type":"TOOL_CALL","toolCode":"payment_query",
                   "args":{"payOrderId":"${slots.payOrderId}","env":"${slots.env}"},
                   "dependsOn":[],"required":true},
                  {"stepId":"log_query","type":"TOOL_CALL","toolCode":"log_query",
                   "args":{"keyword":"${payment_query.errorCode}","timeRange":"1h"},
                   "dependsOn":["payment_query"],"required":false,
                   "condition":"${payment_query.status} == FAILED"}
                ]}
                规则：stepId 统一用 toolCode；args 用 ${slots.field} 引用槽位，${stepId.field} 引用前置步骤字段；
                dependsOn 和 condition 根据 SOP 的业务逻辑推断。
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
              .append(" handleMode=").append(route.handleMode()).append("\n\n");

            // 可用工具声明
            List<com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition> toolDefs =
                    skillRegistry.toolDefinitionsFor(route.domainCode(), route.subDomainCode());
            if (!toolDefs.isEmpty()) {
                sb.append("可用工具：\n");
                toolDefs.forEach(def -> sb.append("  - ").append(def.code())
                        .append(": ").append(def.description()).append("\n"));
                sb.append("\n");
            }

            // 完整 SOP（LLM 从中推断 dependsOn/condition/args）
            String sop = skillRegistry.sopFor(route.domainCode(), route.subDomainCode());
            if (!sop.isBlank()) {
                sb.append("诊断 SOP（根据此 SOP 推断工具调用顺序、依赖和条件）：\n").append(sop).append("\n");
            }
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
