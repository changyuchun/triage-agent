package com.cyc.cyctest.agent.skill;

import java.util.List;

/**
 * SKILL.md 的完整结构化表示——单一真相来源。
 * <p>
 * tool_flow 字段改为只声明可用工具名列表，不再包含 DAG 结构（stepId/dependsOn/condition/args）。
 * DAG 由 Plan 阶段 LLM 根据 SOP 自然语言推断生成，避免 tool_flow 与 SOP 双重维护。
 */
public record SkillMetadata(
        String name,
        String description,
        String domain,
        String subDomain,
        String domainName,
        String subDomainName,
        String domainDescription,
        boolean crossDomain,
        boolean requiresKnowledge,
        ActivationRule activationRule,
        List<String> tools,         // 可用工具 toolCode 列表（只声明，无 DAG 结构）
        String sopContent           // SOP 完整正文，Plan + Synthesize 阶段均使用
) {
    public SkillMetadata {
        tools = tools != null ? List.copyOf(tools) : List.of();
    }
}
