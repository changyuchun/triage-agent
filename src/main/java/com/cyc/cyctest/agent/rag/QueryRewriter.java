package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询改写器（Query Rewriting）- Advanced RAG 核心组件。
 * <p>
 * 用 LLM 将用户的口语化、模糊查询改写为更精确的检索查询，提升向量检索召回率。
 * <p>
 * 三种技术：
 * 1. Query Rewriting：简单改写，去除口语词，补充专业术语
 * 2. Multi-Query：同一问题展开为多个不同角度的子查询，合并召回结果
 * 3. HyDE（Hypothetical Document Embedding）：先让 LLM 生成假设答案，
 *    用假设答案的 Embedding 去检索。假设答案在向量空间中比原始问题更接近目标文档。
 * <p>
 * 类比：搜索引擎的 Query Expansion（查询扩展），目标是提高召回率。
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final String REWRITE_SYSTEM_PROMPT = """
            你是一个专业的搜索查询优化专家。
            将用户的问题改写为 3 个不同角度的检索查询，每行一个，直接输出查询文本，不要编号或解释。
            要求：
            1. 保留原始语义，补充专业术语
            2. 不同查询角度要有区分度
            3. 适合向量相似度检索（语义搜索）
            """;

    private static final String HYDE_SYSTEM_PROMPT = """
            你是一个专家，请根据以下问题生成一段假设性的参考文档内容（100字以内）。
            这段内容将作为"理想答案"用于向量检索。直接输出文档内容，不要解释。
            """;

    private final LlmClient llmClient;

    @Value("${agent.rag.query-rewriting.enabled:true}")
    private boolean rewritingEnabled;

    @Value("${agent.rag.hyde.enabled:false}")
    private boolean hydeEnabled;

    public QueryRewriter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Multi-Query 改写：将一个问题扩展为多个不同角度的查询。
     * 合并多路检索结果后 RRF 融合，覆盖更多相关文档。
     */
    public List<String> expandToMultiQuery(String originalQuery) {
        if (!rewritingEnabled) {
            return List.of(originalQuery);
        }
        try {
            String raw = llmClient.complete(REWRITE_SYSTEM_PROMPT, "用户问题：" + originalQuery);
            List<String> queries = Arrays.stream(raw.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank() && s.length() > 3)
                    .limit(3)
                    .collect(Collectors.toList());
            if (queries.isEmpty()) return List.of(originalQuery);
            // 始终包含原始查询，确保精确匹配不丢失
            if (!queries.contains(originalQuery)) queries.add(0, originalQuery);
            log.debug("[QueryRewriter] 原始查询: {} → 扩展为 {} 个子查询", originalQuery, queries.size());
            return queries;
        } catch (Exception e) {
            log.warn("[QueryRewriter] 改写失败，使用原始查询: {}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    /**
     * HyDE（Hypothetical Document Embedding）：
     * LLM 先生成一个假设性回答，用假设回答的 Embedding 去检索真实文档。
     * 原理：假设答案在向量空间中比原始问题更接近目标文档，提升检索精度。
     */
    public String generateHypotheticalDoc(String originalQuery) {
        if (!hydeEnabled) {
            return originalQuery;
        }
        try {
            String hypothetical = llmClient.complete(HYDE_SYSTEM_PROMPT, originalQuery);
            log.debug("[HyDE] 生成假设文档: {}", hypothetical.substring(0, Math.min(50, hypothetical.length())));
            return hypothetical;
        } catch (Exception e) {
            log.warn("[HyDE] 生成失败，降级使用原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }
}
