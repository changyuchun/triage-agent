package com.cyc.cyctest.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 会话存储（生产模式）。
 * <p>
 * 架构：写透缓存（Write-Through Cache）
 * - 读：先查本地 ConcurrentHashMap，未命中则从 Redis 反序列化。
 * - 写：序列化为 JSON 写入 Redis，同步更新本地缓存。
 * - 多实例：本地缓存不跨实例共享，但 Redis 保证最终一致性。
 * <p>
 * Key 设计：
 * - 会话数据：agent:session:{sessionId}  STRING  TTL=7天
 * - 会话索引：agent:sessions             ZSET    score=createdAt毫秒
 * </p>
 * 通过 agent.memory.store=redis 启用。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.store", havingValue = "redis")
public class RedisSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRepository.class);
    private static final String SESSION_KEY_PREFIX = "agent:session:";
    private static final String SESSIONS_ZSET_KEY = "agent:sessions";

    private final Map<String, ConversationContext> localCache = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlDays;

    public RedisSessionRepository(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   org.springframework.core.env.Environment env) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlDays = Long.parseLong(env.getProperty("agent.memory.ttl-days", "7"));
    }

    @Override
    public ConversationContext loadOrCreate(String sessionId) {
        String key = normalize(sessionId);
        return localCache.computeIfAbsent(key, this::loadFromRedis);
    }

    @Override
    public void save(ConversationContext context) {
        try {
            ConversationSnapshot snapshot = context.toSnapshot();
            String json = objectMapper.writeValueAsString(snapshot);
            String redisKey = SESSION_KEY_PREFIX + context.sessionId();
            redisTemplate.opsForValue().set(redisKey, json, Duration.ofDays(ttlDays));
            redisTemplate.opsForZSet().add(SESSIONS_ZSET_KEY, context.sessionId(),
                    context.createdAt().toEpochMilli());
            localCache.put(context.sessionId(), context);
        } catch (Exception e) {
            log.warn("会话持久化到 Redis 失败，会话 {}，原因: {}", context.sessionId(), e.getMessage());
        }
    }

    @Override
    public List<MemoryStore.SessionSummary> listSessions() {
        try {
            Set<String> sessionIds = redisTemplate.opsForZSet().reverseRange(SESSIONS_ZSET_KEY, 0, 99);
            if (sessionIds == null || sessionIds.isEmpty()) return List.of();
            return sessionIds.stream()
                    .map(this::loadOrCreate)
                    .sorted(Comparator.comparing(ConversationContext::createdAt).reversed())
                    .map(s -> new MemoryStore.SessionSummary(
                            s.sessionId(), s.title(), s.createdAt(), s.clarifyCount(), s.pendingClarifyQuestion()))
                    .toList();
        } catch (Exception e) {
            log.warn("从 Redis 列举会话失败: {}", e.getMessage());
            return localCache.values().stream()
                    .sorted(Comparator.comparing(ConversationContext::createdAt).reversed())
                    .map(s -> new MemoryStore.SessionSummary(
                            s.sessionId(), s.title(), s.createdAt(), s.clarifyCount(), s.pendingClarifyQuestion()))
                    .toList();
        }
    }

    private ConversationContext loadFromRedis(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
            if (json != null && !json.isBlank()) {
                ConversationSnapshot snapshot = objectMapper.readValue(json, ConversationSnapshot.class);
                log.debug("从 Redis 恢复会话: {}", sessionId);
                return ConversationContext.fromSnapshot(snapshot);
            }
        } catch (Exception e) {
            log.warn("从 Redis 反序列化会话 {} 失败，将创建新会话: {}", sessionId, e.getMessage());
        }
        return new ConversationContext(sessionId);
    }

    private static String normalize(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }
}
