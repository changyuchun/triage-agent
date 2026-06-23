package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.PlanStep;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.core.AgentModels.StepType;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.skill.AgentSkill;
import com.cyc.cyctest.agent.skill.AgentSkillRegistry;
import com.cyc.cyctest.agent.tool.MarketingQueryTool;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskPlanner {
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final AgentSkillRegistry skillRegistry;

    public TaskPlanner(LlmClient llmClient, JsonSupport jsonSupport, AgentSkillRegistry skillRegistry) {
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

    private ExecutionPlan llmPlan(String userText, SlotState slots, RouteResult route) {
        // 只把当前领域的 Skill 暴露给 LLM，避免跨领域工具被误调用
        List<ToolDefinition> domainSkillDefs = skillRegistry.findByCategory(route.domainCode())
                .stream().map(AgentSkill::definition).toList();
        String system = "你是 Agent 任务规划模块。你只输出 JSON，不能解释。";
        String user = """
                根据用户问题、槽位、领域路由和可用工具，生成执行计划。
                约束:
                1. step type 只能是 KNOWLEDGE_RETRIEVE 或 TOOL_CALL，不要生成其他类型。
                2. toolCode 只能从下方"当前领域可用 Skill"中选择。
                3. 查询/排查类优先 tool + knowledge；解释类只用 knowledge。
                4. 不要生成写操作。

                用户问题: %s
                槽位: %s
                路由: %s
                当前领域可用 Skill（%s）: %s

                输出 JSON:
                {"steps":[
                  {"stepId":"knowledge_1","type":"KNOWLEDGE_RETRIEVE","toolCode":null,"query":"...","dependsOn":[],"required":false},
                  {"stepId":"tool_1","type":"TOOL_CALL","toolCode":"payment_query","query":null,"dependsOn":[],"required":true}
                ]}
                """.formatted(userText, slots, route, route.domainCode(), jsonSupport.write(domainSkillDefs));
        return jsonSupport.readJsonObject(llmClient.complete(system, user), ExecutionPlan.class);
    }

    private ExecutionPlan validateOrFallback(ExecutionPlan plan, String userText, RouteResult route, SlotState slots) {
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            return rulePlan(userText, slots, route);
        }
        Set<String> allowedTools = domainSkillCodes(route.domainCode());
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
            return rulePlan(userText, slots, route);
        }
        boolean needsKnowledge = route.handleMode().contains("knowledge");
        boolean hasKnowledge = valid.stream().anyMatch(s -> s.type() == StepType.KNOWLEDGE_RETRIEVE);
        if (needsKnowledge && !hasKnowledge) {
            valid.addFirst(new PlanStep("knowledge_guard", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(valid));
    }

    /**
     * 规则规划：遍历所有 Skill，由 Skill 自己声明是否激活（shouldActivate），
     * TaskPlanner 只负责编排，不再硬编码"哪个领域用哪个工具"。
     * <p>
     * 新增领域 Skill 只需实现 AgentSkill 并注册为 @Component，无需改此方法——开闭原则。
     */
    private ExecutionPlan rulePlan(String userText, SlotState slots, RouteResult route) {
        List<PlanStep> steps = new ArrayList<>();

        List<AgentSkill> activeSkills = skillRegistry.listAll().stream()
                .filter(s -> s.shouldActivate(route, slots))
                .toList();

        boolean needKnowledge = route.handleMode().contains("knowledge")
                || activeSkills.stream().anyMatch(AgentSkill::requiresKnowledge);
        if (needKnowledge) {
            steps.add(new PlanStep("knowledge_1", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        for (AgentSkill skill : activeSkills) {
            steps.add(new PlanStep(skill.skillId(), StepType.TOOL_CALL,
                    skill.definition().code(), null, List.of(), true));
        }
        if (steps.isEmpty()) {
            steps.add(new PlanStep("knowledge_fallback", StepType.KNOWLEDGE_RETRIEVE, null, userText, List.of(), false));
        }
        return new ExecutionPlan(List.copyOf(steps));
    }

    /** 当前领域所有 Skill 的 toolCode 集合，用于 LLM 输出校验白名单。 */
    private Set<String> domainSkillCodes(String domainCode) {
        Set<String> codes = skillRegistry.findByCategory(domainCode).stream()
                .map(s -> s.definition().code())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // marketing 暂无 Skill，fallback 到旧工具保持兼容
        if (codes.isEmpty() && "marketing".equals(domainCode)) {
            codes.add(MarketingQueryTool.CODE);
        }
        return codes;
    }

    public ExecutionPlan rewriteRetrievalPlan(ExecutionPlan oldPlan, String originalQuery, String errorCode) {
        List<PlanStep> rewritten = new ArrayList<>(oldPlan.steps());
        String query = originalQuery + " " + (AgentModels.hasText(errorCode) ? errorCode : "") + " 排查 SOP 错误码 状态";
        rewritten.add(new PlanStep("knowledge_retry", StepType.KNOWLEDGE_RETRIEVE, null, query, List.of(), false));
        return new ExecutionPlan(List.copyOf(rewritten));
    }
}
