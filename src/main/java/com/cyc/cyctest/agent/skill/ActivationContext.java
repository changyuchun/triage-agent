package com.cyc.cyctest.agent.skill;

import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;

/**
 * 传递给 ActivationRule.matches() 的上下文，封装路由结果和槽位状态。
 */
public record ActivationContext(RouteResult route, SlotState slots) {
}
