package com.cyc.cyctest.agent.skill;

import com.cyc.cyctest.agent.tool.AiTool;

/**
 * Agent Skill 接口：在 AiTool（代码工具）基础上增加 Skill 分类和版本元数据。
 * <p>
 * Skill 与普通 Tool 的区别：
 * - Tool 是底层能力（数据查询、API 调用）
 * - Skill 是面向领域的聚合能力，可以被 Agent 路由/编排
 * <p>
 * 所有 AgentSkill 实现类同时是 Spring Bean，会被 ToolRegistry 自动收集（父接口 AiTool）。
 */
public interface AgentSkill extends AiTool {

    String skillId();

    String category();

    default String version() {
        return "1.0.0";
    }

    default boolean enabled() {
        return true;
    }
}
