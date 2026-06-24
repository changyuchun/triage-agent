package com.cyc.cyctest.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Spring AI 的 LlmClient 实现（生产推荐）。
 * <p>
 * 关键特性：
 * 1. 简单熔断降级：连续失败超过阈值后自动降级，防止级联失败。
 *    生产环境可替换为 Resilience4j 的 CircuitBreaker（需引入 resilience4j-core 依赖）。
 * 2. Token 流式输出：chatModel.stream() 逐 token 推送，实现真正的 SSE 流式。
 * 3. 可插拔：通过 agent.llm.provider=spring-ai 激活，SiliconFlowLlmClient 自动停用。
 */
@Component
@ConditionalOnProperty(name = "agent.llm.provider", havingValue = "spring-ai")
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);
    private static final int FAILURE_THRESHOLD = 5; // 连续失败 N 次后触发降级

    private final ChatModel chatModel;
    // 简易熔断计数器（生产替换为 Resilience4j CircuitBreaker）
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public SpringAiLlmClient(@Autowired(required = false) ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public boolean available() {
        return chatModel != null;
    }

    /**
     * 同步 LLM 调用，带简易熔断保护。
     * <p>
     * 熔断概念：连续失败超过阈值直接走 fallback，成功后重置计数。
     * 生产替换：引入 resilience4j-core，用 CircuitBreaker.decorateSupplier() 包装。
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new IllegalStateException("Spring AI ChatModel 未配置或不可用");
        }
        if (consecutiveFailures.get() >= FAILURE_THRESHOLD) {
            log.warn("[CB] 连续失败达阈值，触发降级");
            return completeFallback(new RuntimeException("circuit open after " + FAILURE_THRESHOLD + " failures"));
        }
        try {
            String result = doComplete(systemPrompt, userPrompt);
            consecutiveFailures.set(0); // 成功重置
            return result;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.error("[CB] LLM 调用失败（{}/{}）: {}", failures, FAILURE_THRESHOLD, e.getMessage());
            return completeFallback(e);
        }
    }

    /**
     * Token 级流式输出（Stream）。
     * <p>
     * chatModel.stream() 返回 Flux&lt;ChatResponse&gt;，每个元素包含一个 token chunk。
     * SSE 端点消费此 Flux，实现浏览器侧的实时 token 渲染（首字延迟 &lt; 200ms）。
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
     * Fallback：主模型不可用时的降级响应。
     * 生产场景应在此切换到备用模型（DeepSeek → Qwen → 本地 Ollama）。
     */
    private String completeFallback(Throwable throwable) {
        log.warn("[Fallback] 使用降级响应，原因: {}", throwable.getMessage());
        return "当前 AI 服务暂时不可用，请稍后重试。（系统正在自动恢复中）";
    }
}
