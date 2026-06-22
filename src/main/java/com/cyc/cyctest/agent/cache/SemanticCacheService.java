package com.cyc.cyctest.agent.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * 语义缓存服务（Semantic Cache）- 生产级 LLM 成本优化。
 * <p>
 * 核心思路：相似问题（余弦相似度 > 阈值）直接命中缓存，无需重复 LLM 调用。
 * 与传统 KV 缓存的区别：key 不是精确字符串，而是语义向量相似度。
 * <p>
 * 架构：
 * - 本地内存索引（ConcurrentLinkedDeque）：存 embedding 向量，快速遍历计算相似度
 * - Redis（String key）：存缓存答案，TTL 2小时，支持集群部署
 * <p>
 * 类比：Nginx 的 proxy_cache，只不过缓存命中判断从精确 URL 匹配变为语义相似度匹配。
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private static final String KEY_PREFIX = "semantic-cache:";
    private static final int MAX_LOCAL_INDEX_SIZE = 200;

    @Value("${agent.semantic-cache.similarity-threshold:0.92}")
    private double similarityThreshold;

    @Value("${agent.semantic-cache.ttl-hours:2}")
    private long ttlHours;

    @Value("${agent.semantic-cache.enabled:true}")
    private boolean enabled;

    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;

    // 本地 embedding 索引，按访问时间 LRU 淘汰
    private final ConcurrentLinkedDeque<CacheEntry> localIndex = new ConcurrentLinkedDeque<>();

    public SemanticCacheService(@Autowired(required = false) EmbeddingModel embeddingModel,
                                StringRedisTemplate redisTemplate) {
        this.embeddingModel = embeddingModel;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试从语义缓存中查找相似问题的答案。
     *
     * @return 缓存命中时返回答案，否则返回 empty
     */
    public Optional<String> get(String query) {
        if (!enabled || embeddingModel == null || localIndex.isEmpty()) {
            return Optional.empty();
        }
        try {
            float[] queryVec = embed(query);
            String bestKey = null;
            double bestSim = similarityThreshold;

            for (CacheEntry entry : localIndex) {
                double sim = cosineSimilarity(queryVec, entry.embedding());
                if (sim > bestSim) {
                    bestSim = sim;
                    bestKey = entry.redisKey();
                }
            }

            if (bestKey != null) {
                String cached = redisTemplate.opsForValue().get(bestKey);
                if (cached != null) {
                    log.info("[SemanticCache] 命中缓存 similarity={:.3f} query={}", bestSim, truncate(query));
                    return Optional.of(cached);
                }
            }
        } catch (Exception e) {
            log.debug("[SemanticCache] 查询异常（不影响主流程）: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 将问答对写入语义缓存。
     */
    public void put(String query, String answer) {
        if (!enabled || embeddingModel == null || answer == null || answer.isBlank()) {
            return;
        }
        try {
            float[] vec = embed(query);
            String key = KEY_PREFIX + Math.abs(query.hashCode()) + ":" + System.currentTimeMillis() % 10000;
            redisTemplate.opsForValue().set(key, answer, ttlHours, TimeUnit.HOURS);

            // 本地索引 LRU 淘汰
            if (localIndex.size() >= MAX_LOCAL_INDEX_SIZE) {
                localIndex.pollLast();
            }
            localIndex.addFirst(new CacheEntry(key, vec));
            log.debug("[SemanticCache] 写入缓存 key={} query={}", key, truncate(query));
        } catch (Exception e) {
            log.debug("[SemanticCache] 写入异常（不影响主流程）: {}", e.getMessage());
        }
    }

    private float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /** 余弦相似度：向量夹角的余弦值，范围 [-1, 1]，1 表示完全相同方向 */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    private record CacheEntry(String redisKey, float[] embedding) {}
}
