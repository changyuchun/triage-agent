package com.cyc.cyctest.agent.graph.react.skill;

import com.cyc.cyctest.agent.skill.SkillMetadata;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * read_skill 工具：供 ReactAgent 按需加载完整 Skill 内容（SOP + 工具调用流程）。
 * 实现渐进式披露策略——Agent 只在判断需要时才加载完整 Skill 内容。
 */
@Component
public class ReadSkillTool {

    private final SkillRegistry skillRegistry;

    public ReadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Tool(description = "按需加载指定技能（Skill）的完整内容，包含工具调用流程（tool_flow）和排查 SOP。" +
            "当需要了解某个 Skill 的具体操作步骤时调用此工具。skillName 必须是技能列表中的名称。")
    public String read_skill(@ToolParam(description = "技能名称，如 payment-diagnosis") String skillName) {
        return skillRegistry.findByName(skillName)
                .map(this::formatSkill)
                .orElse("Skill 未找到: " + skillName + "。可用技能：" +
                        skillRegistry.allSkills().stream()
                                .map(SkillMetadata::name)
                                .reduce((a, b) -> a + ", " + b).orElse("无"));
    }

    private String formatSkill(SkillMetadata meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(meta.name()).append("\n\n");
        sb.append("**描述**: ").append(meta.description()).append("\n\n");

        if (!meta.tools().isEmpty()) {
            sb.append("## 可用工具\n\n");
            meta.tools().forEach(t -> sb.append("- ").append(t).append("\n"));
            sb.append("\n");
        }

        if (meta.sopContent() != null && !meta.sopContent().isBlank()) {
            sb.append("## 排查 SOP\n\n");
            sb.append(meta.sopContent());
        }

        return sb.toString();
    }
}
