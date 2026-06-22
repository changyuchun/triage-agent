package com.cyc.cyctest.agent.rag;

import java.util.Map;

public final class KnowledgeModels {
    private KnowledgeModels() {
    }

    public record RetrieveRequest(
            String query,
            String domainCode,
            String subDomainCode,
            Map<String, Object> filters,
            int topK
    ) {
    }

    public record KnowledgeChunk(
            String chunkId,
            String docId,
            String domainCode,
            String subDomainCode,
            String title,
            String content,
            double score,
            Map<String, Object> metadata
    ) {
        public KnowledgeChunk withScore(double newScore) {
            return new KnowledgeChunk(chunkId, docId, domainCode, subDomainCode, title, content, newScore, metadata);
        }
    }

    public record FusedResult(KnowledgeChunk chunk, double score, Map<String, Integer> sourceRanks) {
    }
}
