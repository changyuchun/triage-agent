package com.cyc.cyctest.agent.skill;

import java.util.List;

/**
 * SKILL.md 的完整结构化表示——单一真相来源。
 * <p>
 * 替代原有两个独立注册表（AgentSkillRegistry + DomainSkillLoader），
 * 将领域元数据、激活规则、工具调用流程和 SOP 内容统一在一处。
 */
public record SkillMetadata(
        String name,
        String description,
        String domain,              // SKILL.md domain 字段
        String subDomain,           // SKILL.md sub_domain 字段，null = 领域级
        boolean crossDomain,        // domain = "*"
        boolean requiresKnowledge,
        ActivationRule activationRule,
        List<ToolFlowStep> toolFlow,
        String sopContent           // SKILL.md body（只注入 Synthesizer，不注入 Planner）
) {
    public SkillMetadata {
        toolFlow = toolFlow != null ? List.copyOf(toolFlow) : List.of();
    }

    /** 构建用于 Planner prompt 的工具流程摘要（结构化，非完整 SOP）。 */
    public String toolFlowSummary() {
        if (toolFlow.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolFlowStep step : toolFlow) {
            sb.append(String.format("  %s: toolCode=%s", step.stepId(), step.toolCode()));
            if (!step.dependsOn().isEmpty()) {
                sb.append(", dependsOn=").append(step.dependsOn());
            }
            if (step.condition() != null && !step.condition().isBlank()) {
                sb.append(", condition=\"").append(step.condition()).append("\"");
            }
            if (!step.args().isEmpty()) {
                sb.append(", args=").append(step.args());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
