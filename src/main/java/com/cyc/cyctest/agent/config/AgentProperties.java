package com.cyc.cyctest.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        SiliconFlow siliconflow,
        Runtime runtime,
        Memory memory
) {
    public AgentProperties {
        if (siliconflow == null) {
            siliconflow = new SiliconFlow(false, "", "https://api.siliconflow.cn/v1/chat/completions",
                    "Qwen/Qwen2.5-7B-Instruct", 0.2, 4000);
        }
        if (runtime == null) {
            runtime = new Runtime(2, 0.45, 60, 50, 30, 8, 5000, 2);
        }
        if (memory == null) {
            memory = new Memory("in-memory", 7);
        }
    }

    public record SiliconFlow(
            boolean enabled,
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            int timeoutMillis
    ) {
    }

    public record Runtime(
            int maxClarifyRounds,
            double minEvidenceScore,
            int rrfRankConstant,
            int bm25TopK,
            int fusedTopK,
            int finalTopK,
            int toolTimeoutMs,      // 单个工具调用超时毫秒，默认 5000
            int maxToolRetries      // TOOL_EXCEPTION 时最大重试次数，默认 2
    ) {
    }

    /**
     * 会话存储配置。
     * store: in-memory（开发）| redis（生产）
     * ttlDays: 会话 Redis TTL（天），仅在 redis 模式下生效
     */
    public record Memory(String store, int ttlDays) {
    }
}
