package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.FusedResult;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() {
    }

    public static List<FusedResult> fuse(List<List<KnowledgeChunk>> rankedLists, int rankConstant, int topK) {
        Map<String, Builder> scoreMap = new HashMap<>();
        for (int listIndex = 0; listIndex < rankedLists.size(); listIndex++) {
            List<KnowledgeChunk> list = rankedLists.get(listIndex);
            for (int i = 0; i < list.size(); i++) {
                int rank = i + 1;
                KnowledgeChunk chunk = list.get(i);
                double score = 1.0 / (rankConstant + rank);
                scoreMap.computeIfAbsent(chunk.chunkId(), id -> new Builder(chunk))
                        .add(score, "retriever_" + listIndex, rank);
            }
        }
        return scoreMap.values().stream()
                .map(Builder::build)
                .sorted(Comparator.comparingDouble(FusedResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private static final class Builder {
        private final KnowledgeChunk chunk;
        private final Map<String, Integer> ranks = new HashMap<>();
        private double score;

        private Builder(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }

        private Builder add(double value, String source, int rank) {
            score += value;
            ranks.put(source, rank);
            return this;
        }

        private FusedResult build() {
            return new FusedResult(chunk.withScore(score), score, Map.copyOf(ranks));
        }
    }
}
