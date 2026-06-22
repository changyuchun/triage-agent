package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.ChatResponse;

/**
 * Agent 运行时统一接口。
 * <p>
 * 两个实现：
 * - {@link AgentRuntime}：while-switch 状态机（原始实现，保留对比）
 * - {@link GraphAgentRuntime}：Spring AI Alibaba StateGraph（生产推荐）
 * <p>
 * 通过 {@code @Primary} / {@code agent.runtime.type} 配置决定激活哪个实现。
 */
public interface IAgentRuntime {

    /**
     * 执行一轮 Agent 对话。
     *
     * @param sessionId 会话 ID（多轮对话隔离 + Memory 索引）
     * @param userText  用户输入（已经过 Guardrails 清洗）
     * @return 本轮响应（含状态、答案、槽位、证据、Trace）
     */
    ChatResponse run(String sessionId, String userText);
}
