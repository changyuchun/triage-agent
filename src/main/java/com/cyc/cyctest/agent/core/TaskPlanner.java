package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.PlanStep;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.core.AgentModels.StepType;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.skill.ActivationContext;
import com.cyc.cyctest.agent.skill.SkillMetadata;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import com.cyc.cyctest.agent.skill.ToolFlowStep;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskPlanner {

    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final SkillRegistry skillRegistry;

    public TaskPlanner(LlmClient llmClient, JsonSupport jsonSupport, SkillRegistry skillRegistry) {
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
        this.skillRegistry = skillRegistry;
    }

    public ExecutionPlan plan(String userText, SlotState slots, RouteResult route) {
        if (llmClient.available()) {
            try {
                return validateOrFallback(llmPlan(userText, slots, route), userText, route, slots);
            } catch (Exception ignored) {
                return rulePlan(userText, slots, route);
            }
        }
        return rulePlan(userText, slots, route);
    }

    /**
     * LLM 规划：把工具定义 + tool_flow 结构化摘要注入 prompt，
     * LLM 输出带 dependsOn 和精确 args 的执行计划（DAG）。
     */
    private ExecutionPlan llmPlan(String userText, SlotState slots, RouteResult route) {
        // 当前 domain+subDomain 的工具定义和 tool_flow 摘要
        List<ToolDefinition> toolDefs = skillRegistry.toolDefinitionsFor(
                route.domainCode(), route.subDomainCode());
        String toolFlowHint = buildToolFlowHint(route.domainCode(), route.subDomainCode());

        String system = "你是 Agent 任务规划模块。你只输出 JSON，不能解释。";
        String user = """
                根据用户问题、槽位、领域路由和可用工具，生成执行计划（DAG）。
                约束:
                1. step type 只能是 KNOWLEDGE_RETRIEVE 或 TOOL_CALL。
                2. toolCode 只能从"当前领域可用工具"中选择。
                3. TOOL_CALL 步骤必须填写 args（从槽位精确提取，只填有值的参数）。
                4. 如果工具有依赖关系（先查状态再查日志），用 dependsOn 字段声明前置步骤 ID。
                5. 查询/排查类优先 tool + knowledge；解释类只用 knowledge。
                6. 不要生成写操作。

                用户问题: %s
                槽位: %s
                路由: domain=%s subDomain=%s handleMode=%s
                当前领域可用工具: %s
                推荐调用顺序（参考，可根据槽位调整）:
                %s
                输出 JSON:
                {"steps":[
                  {"stepId":"knowledge_1","type":"KNOWLEDGE_RETRIEVE","toolCode":null,"query":"...","args":null,"dependsOn":[],"required":false},
                  {"stepId":"payment_query_step","type":"TOOL_CALL","toolCode":"payment_query",
                   "args":{"payOrderId":"P001","env":"PROD"},"dependsOn":[],"required":true},
                  {"stepId":"log_query_step","type":"TOOL_CALL","toolCode":"log_query",
                   "args":{"keyword":"${payment_query_step.errorCode}","timeRange":"1h"},
                   "dependsOn":["payment_query_step"],"required":false}
                ]}
                """.formatted(
                userText, slots,
                route.domainCode(), route.subDomainCode(), route.handleMode(),
                jsonSupport.write(toolDefs),
                toolFlowHint.isBlank() ? "（无推荐顺序，由 LLM 自行决定）" : toolFlowHint);
        return jsonSupport.readJsonObject(llmClient.complete(system, user), ExecutionPlan.class);
    }

    /**
     * 规则规划：直接从 SkillRegistry 读取 SKILL.md 里的 tool_flow，
     * 构建 DAG 步骤，不再硬编码"哪个领域用哪个工具"。
     */
    private ExecutionPlan rulePlan(String userText, SlotState slots, RouteResult route) {
        ActivationContext ctx = new ActivationContext(route, slots);
        List<SkillMetadata> active = skillRegistry.findActivatable(ctx);

        List<PlanStep> steps = new ArrayList<>();

        boolean needsKnowledge = route.handleMode().contains("knowledge")
                || active.stream().anyMatch(SkillMetadata::requiresKnowledge);
        if (needsKnowledge) {
            steps.add(new PlanStep("knowledge_1", StepType.KNOWLEDGE_RETRIEVE, null,
                    userText, List.of(), false));
        }

        // 直接用 SKILL.md 的 tool_flow 构建步骤（含 dependsOn + args 模板）
        for (SkillMetadata meta : active) {
            for (ToolFlowStep flowStep : meta.toolFlow()) {
                steps.add(new PlanStep(
                        flowStep.stepId(),
                        StepType.TOOL_CALL,
                        flowStep.toolCode(),
                        null,
                        flowStep.dependsOn(),
                        flowStep.required(),
                        new HashMap<>(flowStep.args()),   // String → Object，支持 ${...} 变量引用
                        flowStep.condition()
                ));
            }
        }

        if (steps.isEmpty()) {
            steps.add(new PlanStep("knowledge_fallback", StepType.KNOWLEDGE_RETRIEVE, null,
                    userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(steps));
    }

    private ExecutionPlan validateOrFallback(ExecutionPlan plan, String userText,
                                             RouteResult route, SlotState slots) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return rulePlan(userText, slots, route);
        }
        List<String> allowedCodes = skillRegistry
                .toolDefinitionsFor(route.domainCode(), route.subDomainCode())
                .stream().map(ToolDefinition::code).toList();

        List<PlanStep> valid = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (step.stepId() == null || step.type() == null) continue;
            if (step.type() == StepType.TOOL_CALL && !allowedCodes.contains(step.toolCode())) continue;
            if (step.type() == StepType.KNOWLEDGE_RETRIEVE && !AgentModels.hasText(step.query())) {
                valid.add(new PlanStep(step.stepId(), step.type(), null, userText,
                        step.dependsOn(), step.required()));
            } else {
                valid.add(step);
            }
        }
        if (valid.isEmpty()) return rulePlan(userText, slots, route);

        boolean needsKnowledge = route.handleMode().contains("knowledge");
        boolean hasKnowledge = valid.stream().anyMatch(s -> s.type() == StepType.KNOWLEDGE_RETRIEVE);
        if (needsKnowledge && !hasKnowledge) {
            valid.addFirst(new PlanStep("knowledge_guard", StepType.KNOWLEDGE_RETRIEVE,
                    null, userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(valid));
    }

    /** 构建 tool_flow 文字摘要注入 Planner prompt（非完整 SOP，只有顺序和依赖）。 */
    private String buildToolFlowHint(String domain, String subDomain) {
        StringBuilder sb = new StringBuilder();
        for (SkillMetadata meta : skillRegistry.findByDomain(domain, subDomain)) {
            String summary = meta.toolFlowSummary();
            if (!summary.isBlank()) {
                sb.append("# ").append(meta.name()).append("\n").append(summary);
            }
        }
        return sb.toString();
    }

    public ExecutionPlan rewriteRetrievalPlan(ExecutionPlan oldPlan, String originalQuery, String errorCode) {
        List<PlanStep> rewritten = new ArrayList<>(oldPlan.steps());
        String query = originalQuery + " " + (AgentModels.hasText(errorCode) ? errorCode : "") + " 排查 SOP 错误码 状态";
        rewritten.add(new PlanStep("knowledge_retry", StepType.KNOWLEDGE_RETRIEVE, null, query, List.of(), false));
        return new ExecutionPlan(List.copyOf(rewritten));
    }
}
