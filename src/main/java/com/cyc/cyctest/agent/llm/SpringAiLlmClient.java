package com.cyc.cyctest.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 基于 Spring AI 的 LlmClient 实现（生产推荐）。
 * <p>
 * 关键特性：
 * 1. Circuit Breaker（熔断器）：LLM 调用失败率 > 50% 时自动熔断，降级到备用回答，
 *    防止级联失败。类比 Sentinel 熔断——主渠道故障时自动切换备用渠道。
 * 2. Token 流式输出：chatModel.stream() 逐 token 推送，实现真正的 SSE 流式。
 * 3. 可插拔：通过 agent.llm.provider=spring-ai 激活，SiliconFlowLlmClient 自动停用。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "spring-ai")
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);
    private static final String CB_NAME = "llm-client";

    private final ChatModel chatModel;
    private final ReactiveCircuitBreaker circuitBreaker;

    public SpringAiLlmClient(
            @Autowired(required = false) ChatModel chatModel,
            @Autowired(required = false) ReactiveCircuitBreakerFactory<?, ?> cbFactory) {
        this.chatModel = chatModel;
        this.circuitBreaker = cbFactory != null ? cbFactory.create(CB_NAME) : null;
    }

    @Override
    public boolean available() {
        return chatModel != null;
    }

    /**
     * 同步 LLM 调用，带 Circuit Breaker 熔断保护。
     * <p>
     * 熔断状态机：CLOSED（正常）→ OPEN（熔断，直接走 fallback）→ HALF_OPEN（探测恢复）。
     * 配置在 application.properties resilience4j.circuitbreaker.instances.llm-client.*
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new IllegalStateException("Spring AI ChatModel 未配置或不可用");
        }
        if (circuitBreaker == null) {
            return doComplete(systemPrompt, userPrompt);
        }
        return circuitBreaker.run(
                Mono.fromCallable(() -> doComplete(systemPrompt, userPrompt)),
                throwable -> {
                    log.error("[CB] LLM 调用失败，熔断 fallback 启动: {}", throwable.getMessage());
                    return Mono.just(completeFallback(throwable));
                }
        ).block();
    }

    /**
     * Token 级流式输出（Stream）。
     * <p>
     * chatModel.stream() 返回 Flux<ChatResponse>，每个元素包含一个 token chunk。
     * SSE 端点消费此 Flux，实现浏览器侧的实时 token 渲染（首字延迟 < 200ms）。
     */
    @Override
    public Flux<String> streamTokens(String systemPrompt, String userPrompt) {
        if (!available()) {
            return Flux.error(new IllegalStateException("Spring AI ChatModel 未配置"));
        }
        Prompt prompt = buildPrompt(systemPrompt, userPrompt);
        return chatModel.stream(prompt)
                .mapNotNull(r -> r.getResult() != null && r.getResult().getOutput() != null
                        ? r.getResult().getOutput().getText() : null)
                .filter(t -> t != null && !t.isEmpty())
                .doOnError(e -> log.error("[Stream] LLM 流式调用异常: {}", e.getMessage()));
    }

    private String doComplete(String systemPrompt, String userPrompt) {
        Prompt prompt = buildPrompt(systemPrompt, userPrompt);
        var response = chatModel.call(prompt);
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("LLM 返回空响应");
        }
        return response.getResult().getOutput().getText();
    }

    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        OpenAiChatOptions options = OpenAiChatOptions.builder().temperature(0.2).build();
        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)), options);
    }

    /**
     * Fallback Chain：主模型熔断时返回的降级响应。
     * 生产场景应在此切换到备用模型（如 DeepSeek → Qwen → 本地 Ollama）。
     */
    private String completeFallback(Throwable throwable) {
        log.warn("[Fallback] 使用降级响应，原因: {}", throwable.getMessage());
        return "当前 AI 服务暂时不可用，请稍后重试。（系统正在自动恢复中）";
    }
}
