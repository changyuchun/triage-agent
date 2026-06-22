package com.cyc.cyctest.agent.rag.vector;

import com.cyc.cyctest.agent.rag.KnowledgeCorpus;
import com.cyc.cyctest.agent.rag.ingest.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 应用启动时将硬编码 KnowledgeCorpus 灌入 VectorStore。
 * <p>
 * 幂等保护：首次启动后在 Redis 写入标记 {@code agent:corpus:builtin:loaded}，
 * 后续重启跳过加载，避免向量重复积累。
 * 若需重新加载，请手动删除该 Redis Key。
 */
@Component
@ConditionalOnProperty(name = "agent.rag.retriever", havingValue = "spring-ai")
public class KnowledgeCorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCorpusLoader.class);
    private static final String LOADED_FLAG_KEY = "agent:corpus:builtin:loaded";

    private final KnowledgeCorpus corpus;
    private final DocumentIngestionService ingestionService;
    private final StringRedisTemplate redisTemplate;

    public KnowledgeCorpusLoader(KnowledgeCorpus corpus,
                                  DocumentIngestionService ingestionService,
                                  StringRedisTemplate redisTemplate) {
        this.corpus = corpus;
        this.ingestionService = ingestionService;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadBuiltinKnowledge() {
        Boolean alreadyLoaded = redisTemplate.hasKey(LOADED_FLAG_KEY);
        if (Boolean.TRUE.equals(alreadyLoaded)) {
            log.info("内置知识库已加载（Redis 标记存在），跳过重复索引。如需重新加载，删除 Redis Key: {}", LOADED_FLAG_KEY);
            return;
        }
        log.info("首次启动，开始加载内置知识库...");
        ingestionService.ingestBuiltinCorpus(corpus);
        redisTemplate.opsForValue().set(LOADED_FLAG_KEY, "true");
        log.info("内置知识库加载完成，已设置 Redis 标记: {}", LOADED_FLAG_KEY);
    }
}
