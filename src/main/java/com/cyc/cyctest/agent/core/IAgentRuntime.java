package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.AgentProgressEvent;
import com.cyc.cyctest.agent.core.AgentModels.ChatResponse;
import com.cyc.cyctest.agent.core.AgentModels.ProgressCallback;
import reactor.core.publisher.Flux;

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
     * 执行一轮 Agent 对话（阻塞，返回完整响应）。
     */
    ChatResponse run(String sessionId, String userText);

    /**
     * 带进度回调的同步执行。
     * 各阶段开始/完成时调用 callback，SSE 端点可借此实时推送进度事件。
     * 默认降级为无回调的 run()；AgentRuntime override 实现真实回调。
     */
    default ChatResponse runWithCallback(String sessionId, String userText, ProgressCallback callback) {
        return run(sessionId, userText);
    }

    /**
     * 流式执行一轮 Agent 对话。
     * <p>
     * 事件顺序：Progress（各阶段进度）→ Token（LLM 逐 token）→ Done（完整响应）。
     * 默认实现降级为阻塞 run()，包装成单个 Done 事件；子类可 override 实现真流式。
     */
    default Flux<AgentProgressEvent> stream(String sessionId, String userText) {
        return Flux.just(new AgentProgressEvent.Done(run(sessionId, userText)));
    }
}
