package com.cyc.cyctest.agent.skill;

import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.tool.AiTool;

/**
 * Agent Skill 接口：在 AiTool（数据查询/API 调用）基础上增加领域规划语义。
 * <p>
 * Skill 与普通 Tool 的区别：
 * - Tool：只有执行能力（execute），由外部决定是否调用
 * - Skill：自带激活条件（shouldActivate），TaskPlanner 只负责编排，
 *   不再硬编码"哪个领域用哪个工具"——新增 Skill 只需实现此接口，无需改 TaskPlanner
 * <p>
 * 规划逻辑内聚在 Skill 里，符合开闭原则：对扩展开放，对修改关闭。
 */
public interface AgentSkill extends AiTool {

    String skillId();

    String category();

    /**
     * 声明激活条件：在当前路由和槽位下，此 Skill 是否应被纳入执行计划。
     * TaskPlanner.rulePlan() 遍历所有 Skill，只有返回 true 的才生成 TOOL_CALL 步骤。
     */
    boolean shouldActivate(RouteResult route, SlotState slots);

    /**
     * 此 Skill 执行时是否需要配合知识库检索（KNOWLEDGE_RETRIEVE）。
     * 返回 true 时 TaskPlanner 会自动在计划中加入知识检索步骤。
     */
    default boolean requiresKnowledge() {
        return false;
    }

    default String version() {
        return "1.0.0";
    }

    default boolean enabled() {
        return true;
    }
}
