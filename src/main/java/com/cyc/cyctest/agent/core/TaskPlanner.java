package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.PlanStep;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.core.AgentModels.StepType;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.tool.MarketingQueryTool;
import com.cyc.cyctest.agent.tool.PaymentQueryTool;
import com.cyc.cyctest.agent.tool.ToolRegistry;
import com.cyc.cyctest.agent.tool.TradeQueryTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class TaskPlanner {
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final ToolRegistry toolRegistry;

    public TaskPlanner(LlmClient llmClient, JsonSupport jsonSupport, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
        this.toolRegistry = toolRegistry;
    }

    public ExecutionPlan plan(String userText, SlotState slots, RouteResult route) {
        if (llmClient.available()) {
            try {
                return validateOrFallback(llmPlan(userText, slots, route), userText, route);
            } catch (Exception ignored) {
                return rulePlan(userText, route);
            }
        }
        return rulePlan(userText, route);
    }

    private ExecutionPlan llmPlan(String userText, SlotState slots, RouteResult route) {
        String system = "你是 Agent 任务规划模块。你只输出 JSON，不能解释。";
        String user = """
                根据用户问题、槽位、领域路由和可用工具，生成执行计划。
                约束:
                1. step type 只能是 KNOWLEDGE_RETRIEVE, TOOL_CALL, DOMAIN_ANALYSIS。
                2. toolCode 只能从可用工具选择。
                3. 查询/排查类优先 tool + knowledge；解释类只用 knowledge。
                4. 不要生成写操作。

                用户问题: %s
                槽位: %s
                路由: %s
                可用工具: %s

                输出 JSON:
                {"steps":[
                  {"stepId":"knowledge_1","type":"KNOWLEDGE_RETRIEVE","toolCode":null,"query":"...","dependsOn":[],"required":false},
                  {"stepId":"tool_1","type":"TOOL_CALL","toolCode":"payment.query","query":null,"dependsOn":[],"required":true},
                  {"stepId":"analysis_1","type":"DOMAIN_ANALYSIS","toolCode":null,"query":"基于证据做领域分析","dependsOn":["knowledge_1","tool_1"],"required":false}
                ]}
                """.formatted(userText, slots, route, jsonSupport.write(toolRegistry.definitions()));
        return jsonSupport.readJsonObject(llmClient.complete(system, user), ExecutionPlan.class);
    }

    private ExecutionPlan validateOrFallback(ExecutionPlan plan, String userText, RouteResult route) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return rulePlan(userText, route);
        }
        Set<String> allowedTools = allowedTools(route.domainCode());
        List<PlanStep> valid = new ArrayList<>();
        Set<String> stepIds = new LinkedHashSet<>();
        for (PlanStep step : plan.steps()) {
            if (step.stepId() == null || step.type() == null || !stepIds.add(step.stepId())) {
                continue;
            }
            if (step.type() == StepType.TOOL_CALL && !allowedTools.contains(step.toolCode())) {
                continue;
            }
            if (step.type() == StepType.KNOWLEDGE_RETRIEVE && !AgentModels.hasText(step.query())) {
                valid.add(new PlanStep(step.stepId(), step.type(), null, userText, step.dependsOn(), step.required()));
            } else {
                valid.add(step);
            }
        }
        if (valid.isEmpty()) {
            return rulePlan(userText, route);
        }
        boolean needsKnowledge = route.handleMode().contains("knowledge");
        boolean hasKnowledge = valid.stream().anyMatch(s -> s.type() == StepType.KNOWLEDGE_RETRIEVE);
        if (needsKnowledge && !hasKnowledge) {
            valid.addFirst(new PlanStep("knowledge_guard", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(valid));
    }

    private ExecutionPlan rulePlan(String userText, RouteResult route) {
        List<PlanStep> steps = new ArrayList<>();
        String mode = route.handleMode();
        if ("knowledge_only".equals(mode) || "knowledge_and_tool".equals(mode)) {
            steps.add(new PlanStep("knowledge_1", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        String toolCode = toolCodeForDomain(route.domainCode());
        if (("tool_only".equals(mode) || "knowledge_and_tool".equals(mode)) && toolCode != null) {
            steps.add(new PlanStep("tool_1", StepType.TOOL_CALL, toolCode, null, List.of(), true));
        }
        if ("knowledge_and_tool".equals(mode)) {
            steps.add(new PlanStep("analysis_1", StepType.DOMAIN_ANALYSIS, null, "基于工具和知识证据做领域分析",
                    List.of("knowledge_1", "tool_1"), false));
        }
        if (steps.isEmpty()) {
            steps.add(new PlanStep("knowledge_fallback", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(steps));
    }

    private Set<String> allowedTools(String domainCode) {
        String toolCode = toolCodeForDomain(domainCode);
        return toolCode == null ? Set.of() : Set.of(toolCode);
    }

    private String toolCodeForDomain(String domainCode) {
        return switch (domainCode) {
            case "payment" -> PaymentQueryTool.CODE;
            case "trade" -> TradeQueryTool.CODE;
            case "marketing" -> MarketingQueryTool.CODE;
            default -> null;
        };
    }

    public ExecutionPlan rewriteRetrievalPlan(ExecutionPlan oldPlan, String originalQuery, String errorCode) {
        List<PlanStep> rewritten = new ArrayList<>(oldPlan.steps());
        String query = originalQuery + " " + (AgentModels.hasText(errorCode) ? errorCode : "") + " 排查 SOP 错误码 状态";
        rewritten.add(new PlanStep("knowledge_retry", StepType.KNOWLEDGE_RETRIEVE, null, query, List.of(), false));
        return new ExecutionPlan(List.copyOf(rewritten));
    }
}
