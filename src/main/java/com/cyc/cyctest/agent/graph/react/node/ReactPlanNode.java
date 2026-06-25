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
                你是任务规划专家。根据路由结果和可用工具，制定执行步骤列表。
                只输出 JSON，格式如下：
                {"steps":[
                  {"stepId":"step1","type":"TOOL_CALL","toolCode":"payment_query","query":"查询支付订单","dependsOn":[],"required":true},
                  {"stepId":"step2","type":"TOOL_CALL","toolCode":"log_query","query":"查询错误日志","dependsOn":["step1"],"required":false}
                ]}
                type 只能是 TOOL_CALL 或 KNOWLEDGE_RETRIEVE。
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

            // 提供 Skill 的 tool_flow 作为规划参考
            skillRegistry.findByDomain(route.domainCode(), route.subDomainCode()).forEach(meta -> {
                if (!meta.toolFlow().isEmpty()) {
                    sb.append("参考工具流程（").append(meta.name()).append("）：\n");
                    sb.append(meta.toolFlowSummary());
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
