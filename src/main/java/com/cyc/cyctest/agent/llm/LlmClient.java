package com.cyc.cyctest.agent.llm;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM 客户端接口（Port）- 可插拔设计。
 * <p>
 * 实现：
 * - SpringAiLlmClient：Spring AI ChatModel（生产推荐，含 Circuit Breaker）
 * - SiliconFlowLlmClient：直调 RestClient（开发调试用）
 * <p>
 * 关键设计：streamTokens() 支持 Token 级流式输出（SSE 逐 token 推送），
 * 默认实现降级为单次 complete()，确保非流式实现也能使用此接口。
 */
public interface LlmClient {

    String complete(String systemPrompt, String userPrompt);

    default boolean available() {
        return true;
    }

    /**
     * Token 级流式输出。
     * <p>
     * 返回 Flux<String>，每个元素是一个 token chunk。
     * 适用于 SSE 端点：前端 EventSource 接收逐 token 推送，首字延迟大幅降低。
     * <p>
     * 默认实现：降级为完整输出（单元素 Flux），确保不支持流式的实现不会编译失败。
     */
    default Flux<String> streamTokens(String systemPrompt, String userPrompt) {
        return Flux.just(complete(systemPrompt, userPrompt));
    }

    record Message(String role, String content) {}

    record ChatCompletionRequest(String model, List<Message> messages, double temperature) {}

    record ChatCompletionResponse(List<Choice> choices) {}

    record Choice(Message message) {}
}
