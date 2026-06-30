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
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
     * LLM 规划：注入完整 SOP + 工具定义，LLM 根据 SOP 自然语言推断 DAG
     * （stepId=toolCode、dependsOn、condition、args 均由 LLM 从 SOP 中推断）。
     */
    private ExecutionPlan llmPlan(String userText, SlotState slots, RouteResult route) {
        boolean needsTools = !"knowledge_only".equals(route.handleMode());
        List<ToolDefinition> toolDefs = needsTools
                ? skillRegistry.toolDefinitionsFor(route.domainCode(), route.subDomainCode())
                : List.of();
        String sop = skillRegistry.sopFor(route.domainCode(), route.subDomainCode());

        String system = "你是 Agent 任务规划模块。你只输出 JSON，不能解释。";
        String user = """
                根据用户问题、槽位、领域路由、可用工具定义和诊断 SOP，生成执行计划（DAG）。

                规则：
                1. type 只能是 KNOWLEDGE_RETRIEVE 或 TOOL_CALL。
                2. toolCode 只能从"可用工具"中选择。
                3. stepId 统一用 toolCode 本身（同工具多次调用时加 _2/_3 后缀）。
                4. args 用 ${slots.field} 引用槽位，用 ${stepId.field} 引用前置步骤返回字段。
                5. 根据 SOP 中描述的业务逻辑推断 dependsOn 和 condition。
                   例如："若 status=FAILED 则查日志" → condition="${payment_query.status} == FAILED"
                   例如："用 traceId 查日志" → args.keyword="${payment_query.traceId}"
                6. 查询/排查类优先 tool + knowledge；解释类只用 knowledge。
                7. 不要生成写操作。

                用户问题: %s
                槽位: %s
                路由: domain=%s subDomain=%s handleMode=%s

                可用工具:
                %s

                诊断 SOP（从此推断工具调用顺序、依赖关系和条件分支）:
                %s

                输出 JSON:
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
                """.formatted(
                userText, slots,
                route.domainCode(), route.subDomainCode(), route.handleMode(),
                jsonSupport.write(toolDefs),
                sop.isBlank() ? "（无 SOP，根据工具定义自行决定调用顺序）" : sop);

        return jsonSupport.readJsonObject(llmClient.complete(system, user), ExecutionPlan.class);
    }

    /**
     * 规则规划（LLM 不可用时的降级）：按工具列表顺序创建步骤，无依赖关系，
     * args 仅用槽位默认值，无法做跨步骤的变量引用。
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

        // 按工具声明顺序创建步骤，无依赖（降级模式，无法做跨步骤变量引用）
        for (SkillMetadata meta : active) {
            for (String toolCode : meta.tools()) {
                if (steps.stream().anyMatch(s -> toolCode.equals(s.toolCode()))) continue;
                steps.add(new PlanStep(toolCode, StepType.TOOL_CALL, toolCode,
                        null, List.of(), false,
                        slotArgs(slots)));
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

    /** 降级时槽位默认参数（无跨步骤引用）。 */
    private Map<String, Object> slotArgs(SlotState slots) {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        if (AgentModels.hasText(slots.payOrderId())) args.put("payOrderId", slots.payOrderId());
        if (AgentModels.hasText(slots.orderId())) args.put("orderId", slots.orderId());
        if (AgentModels.hasText(slots.couponId())) args.put("couponId", slots.couponId());
        if (AgentModels.hasText(slots.activityId())) args.put("activityId", slots.activityId());
        if (slots.env() != null) args.put("env", slots.env().name());
        return args;
    }

    public ExecutionPlan rewriteRetrievalPlan(ExecutionPlan oldPlan, String originalQuery, String errorCode) {
        List<PlanStep> rewritten = new ArrayList<>(oldPlan.steps());
        String query = originalQuery + " " + (AgentModels.hasText(errorCode) ? errorCode : "") + " 排查 SOP 错误码 状态";
        rewritten.add(new PlanStep("knowledge_retry", StepType.KNOWLEDGE_RETRIEVE, null, query, List.of(), false));
        return new ExecutionPlan(List.copyOf(rewritten));
    }
}
