package com.cyc.cyctest.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson 全局 ObjectMapper 配置。
 * <p>
 * 核心需求：ConversationSnapshot 中的 {@code Instant} 字段需要 JavaTimeModule 支持，
 * 否则 Redis 序列化会失败。同时确保所有 HTTP 响应中的时间字段输出为 ISO-8601 字符串。
 */
@Configuration
public class AgentRedisConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
