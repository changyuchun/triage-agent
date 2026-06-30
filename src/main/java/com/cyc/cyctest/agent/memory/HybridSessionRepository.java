package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.memory.ConversationContext.MemoryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混合存储（生产推荐）：MySQL 为主，Redis 为热缓存。
 * <p>
 * 读：本地缓存 → Redis（TTL=2h）→ MySQL → new
 * 写：同步写 MySQL（主）+ 更新 Redis（缓存）
 * <p>
 * 与 RedisSessionRepository 的核心区别：
 * - 数据真正活在 MySQL，Redis TTL 到期只是缓存失效，不是数据丢失
 * - 支持按 userId 隔离查询会话列表
 * - 归档只需更新 MySQL（archivedAt + turnsJson=null），Redis 自然过期
 * <p>
 * 通过 agent.memory.store=hybrid 启用。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.store", havingValue = "hybrid")
public class HybridSessionRepository implements SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(HybridSessionRepository.class);
    private static final String REDIS_PREFIX = "agent:session:";
    private static final Duration REDIS_TTL = Duration.ofHours(2);

    private final AgentSessionJpaRepository jpaRepo;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryPolicy memoryPolicy;
    private final Map<String, ConversationContext> localCache = new ConcurrentHashMap<>();

    public HybridSessionRepository(AgentSessionJpaRepository jpaRepo,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   AgentProperties properties) {
        this.jpaRepo = jpaRepo;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.memoryPolicy = MemoryPolicy.from(properties.memory());
    }

    @Override
    public ConversationContext loadOrCreate(String sessionId) {
        return localCache.computeIfAbsent(sessionId, this::loadOrCreateInternal);
    }

    private ConversationContext loadOrCreateInternal(String sessionId) {
        // L1: Redis 热缓存
        ConversationContext ctx = loadFromRedis(sessionId);
        if (ctx != null) return ctx;
        // L2: MySQL 主存储
        ctx = loadFromDb(sessionId);
        if (ctx != null) {
            writeToRedis(ctx); // 写回热缓存
            return ctx;
        }
        // L3: 全新会话
        return new ConversationContext(sessionId, memoryPolicy);
    }

    @Override
    public void save(ConversationContext ctx) {
        try {
            // 主写 MySQL
            saveToDb(ctx);
            // 更新 Redis 热缓存
            writeToRedis(ctx);
            localCache.put(ctx.sessionId(), ctx);
        } catch (Exception e) {
            log.warn("会话 {} 保存失败: {}", ctx.sessionId(), e.getMessage());
        }
    }

    @Override
    public List<MemoryStore.SessionSummary> listSessions() {
        return jpaRepo.findAll().stream()
                .sorted(Comparator.comparing(AgentSessionEntity::getLastActiveAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> new MemoryStore.SessionSummary(
                        e.getSessionId(), e.getTitle(), e.getCreatedAt(), 0, null))
                .toList();
    }

    @Override
    public List<String> findOldSessionIds(int inactiveDays) {
        Instant threshold = Instant.now().minusSeconds((long) inactiveDays * 86400);
        return jpaRepo.findOldActiveSessionIds(threshold);
    }

    @Override
    public void deleteSession(String sessionId) {
        jpaRepo.deleteById(sessionId);
        redisTemplate.delete(REDIS_PREFIX + sessionId);
        localCache.remove(sessionId);
    }

    // ---- 内部 Redis 读写 ----

    private ConversationContext loadFromRedis(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_PREFIX + sessionId);
            if (json != null && !json.isBlank()) {
                ConversationSnapshot snap = objectMapper.readValue(json, ConversationSnapshot.class);
                return ConversationContext.fromSnapshot(snap, memoryPolicy);
            }
        } catch (Exception e) {
            log.warn("从 Redis 恢复会话 {} 失败: {}", sessionId, e.getMessage());
        }
        return null;
    }

    private void writeToRedis(ConversationContext ctx) {
        try {
            String json = objectMapper.writeValueAsString(ctx.toSnapshot());
            redisTemplate.opsForValue().set(REDIS_PREFIX + ctx.sessionId(), json, REDIS_TTL);
        } catch (Exception e) {
            log.warn("写入 Redis 失败 session={}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    // ---- 内部 DB 读写 ----

    private ConversationContext loadFromDb(String sessionId) {
        return jpaRepo.findById(sessionId).map(e -> {
            try {
                ConversationSnapshot snap = objectMapper.readValue(e.getTurnsJson() != null
                        ? buildSnapshotJson(e) : buildSnapshotJson(e), ConversationSnapshot.class);
                return ConversationContext.fromSnapshot(snap, memoryPolicy);
            } catch (Exception ex) {
                log.warn("从 DB 反序列化会话 {} 失败: {}", sessionId, ex.getMessage());
                return null;
            }
        }).orElse(null);
    }

    private void saveToDb(ConversationContext ctx) throws Exception {
        String snapshotJson = objectMapper.writeValueAsString(ctx.toSnapshot());
        ConversationSnapshot snap = ctx.toSnapshot();
        String slotJson = objectMapper.writeValueAsString(snap.slotState());
        String turnsJson = objectMapper.writeValueAsString(snap.structuredTurns());

        AgentSessionEntity entity = jpaRepo.findById(ctx.sessionId()).orElse(null);
        if (entity == null) {
            entity = new AgentSessionEntity(
                    ctx.sessionId(), null, ctx.title(),
                    snap.summary(), slotJson, turnsJson,
                    ctx.createdAt(), ctx.lastActiveAt());
        } else {
            entity.setTitle(ctx.title());
            entity.setSummary(snap.summary());
            entity.setSlotStateJson(slotJson);
            entity.setTurnsJson(turnsJson);
            entity.setLastActiveAt(ctx.lastActiveAt());
        }
        jpaRepo.save(entity);
    }

    private String buildSnapshotJson(AgentSessionEntity e) throws Exception {
        // 把 DB 行重组成 ConversationSnapshot JSON 交给 ObjectMapper 反序列化
        // 最简单：把 DB 里单独存的字段和 turns 合成一个临时 snapshot
        // 这里直接用 turns_json 作为 structuredTurns 的来源
        // 完整实现应存完整 snapshot JSON；此处 minimal 处理
        return "{\"sessionId\":\"" + e.getSessionId() + "\","
                + "\"title\":\"" + (e.getTitle() == null ? "" : e.getTitle()) + "\","
                + "\"summary\":\"" + (e.getSummary() == null ? "暂无摘要" : e.getSummary().replace("\"", "\\\"")) + "\","
                + "\"createdAt\":\"" + (e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt()) + "\","
                + "\"lastActiveAt\":\"" + (e.getLastActiveAt() == null ? Instant.now() : e.getLastActiveAt()) + "\","
                + "\"structuredTurns\":" + (e.getTurnsJson() != null ? e.getTurnsJson() : "[]") + ","
                + "\"compressedTurnCount\":0,\"clarifyCount\":0}";
    }
}
