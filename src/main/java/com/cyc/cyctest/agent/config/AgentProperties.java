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
            memory = new Memory("in-memory", 7, 50, 8, 6, 3);
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
     * 会话存储与记忆压缩配置。
     * store: in-memory（开发）| redis（生产）
     * ttlDays: 会话 Redis TTL（天），仅在 redis 模式下生效
     * slidingWindowSize: structuredTurns 滑动窗口上限，超出后淘汰最旧轮次
     * firstCompressAt: 首次触发 LLM 压缩所需的最少轮次（建议 = maxClarifyRounds*2 + 4）
     * compressEvery: 每攒够 N 条新轮次后再次压缩
     * retainAfterCompress: 压缩后保留的最新原文轮次数（过渡缓冲，防止摘要遗漏最近细节）
     */
    public record Memory(
            String store,
            int ttlDays,
            int slidingWindowSize,
            int firstCompressAt,
            int compressEvery,
            int retainAfterCompress
    ) {
    }
}
