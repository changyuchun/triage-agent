package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class Bm25Retriever {
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private final KnowledgeCorpus corpus;
    private final RuntimeKnowledgeIndex runtimeIndex;

    public Bm25Retriever(KnowledgeCorpus corpus, RuntimeKnowledgeIndex runtimeIndex) {
        this.corpus = corpus;
        this.runtimeIndex = runtimeIndex;
    }

    public List<KnowledgeChunk> search(RetrieveRequest request, int topK) {
        List<KnowledgeChunk> docs = filtered(request);
        if (docs.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = tokenize(request.query());
        double avgLen = docs.stream().mapToInt(d -> tokenize(d.title() + " " + d.content()).size()).average().orElse(1.0);

        Map<String, Integer> docFreq = new HashMap<>();
        for (KnowledgeChunk doc : docs) {
            List<String> terms = tokenize(doc.title() + " " + doc.content()).stream().distinct().toList();
            for (String term : terms) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        List<KnowledgeChunk> scored = new ArrayList<>();
        for (KnowledgeChunk doc : docs) {
            List<String> terms = tokenize(doc.title() + " " + doc.content());
            Map<String, Integer> tf = new HashMap<>();
            for (String term : terms) {
                tf.merge(term, 1, Integer::sum);
            }
            double score = 0;
            for (String q : queryTerms) {
                int freq = tf.getOrDefault(q, 0);
                if (freq == 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(q, 0);
                double idf = Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5));
                double denominator = freq + K1 * (1 - B + B * terms.size() / avgLen);
                score += idf * (freq * (K1 + 1)) / denominator;
            }
            if (score > 0) {
                scored.add(doc.withScore(score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(KnowledgeChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9_]+", " ");
        List<String> terms = new ArrayList<>();
        for (String raw : normalized.split("\\s+")) {
            if (raw.isBlank()) {
                continue;
            }
            terms.add(raw);
            if (raw.length() > 2 && raw.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < raw.length() - 1; i++) {
                    terms.add(raw.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private List<KnowledgeChunk> filtered(RetrieveRequest request) {
        List<KnowledgeChunk> searchableChunks = new ArrayList<>(corpus.chunks());
        searchableChunks.addAll(runtimeIndex.chunks());
        return searchableChunks.stream()
                .filter(c -> request.domainCode() == null || request.domainCode().equals(c.domainCode()))
                .filter(c -> request.subDomainCode() == null || request.subDomainCode().equals(c.subDomainCode())
                        || c.subDomainCode().endsWith("knowledge"))
                .toList();
    }
}
