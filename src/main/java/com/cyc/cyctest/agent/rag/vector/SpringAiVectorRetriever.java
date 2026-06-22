package com.cyc.cyctest.agent.rag.vector;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import com.cyc.cyctest.agent.rag.SemanticRetriever;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 基于 Spring AI VectorStore 的真实语义检索实现。
 * 使用 EmbeddingModel 将查询文本转为向量，在 Redis VectorStore 中做 ANN 近似搜索。
 */
@Component
@ConditionalOnProperty(name = "agent.rag.retriever", havingValue = "spring-ai")
public class SpringAiVectorRetriever implements SemanticRetriever {

    private final VectorStore vectorStore;

    public SpringAiVectorRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<KnowledgeChunk> search(RetrieveRequest request, int topK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.query())
                .topK(topK);

        // 构建 metadata 过滤条件
        if (request.domainCode() != null && !request.domainCode().isBlank()) {
            builder.filterExpression("domainCode == '" + request.domainCode() + "'");
        }

        List<Document> docs = vectorStore.similaritySearch(builder.build());
        return docs.stream()
                .map(this::toChunk)
                .toList();
    }

    private KnowledgeChunk toChunk(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        double score = extractScore(meta);
        return new KnowledgeChunk(
                doc.getId(),
                String.valueOf(meta.getOrDefault("docId", doc.getId())),
                String.valueOf(meta.getOrDefault("domainCode", "")),
                String.valueOf(meta.getOrDefault("subDomainCode", "")),
                String.valueOf(meta.getOrDefault("title", "")),
                doc.getText(),
                score,
                meta
        );
    }

    private double extractScore(Map<String, Object> meta) {
        Object score = meta.get("distance");
        if (score instanceof Number n) {
            // Redis VectorStore 返回 cosine distance，转为相似度
            return 1.0 - n.doubleValue();
        }
        Object sim = meta.get("score");
        if (sim instanceof Number n) {
            return n.doubleValue();
        }
        return 0.5;
    }
}
