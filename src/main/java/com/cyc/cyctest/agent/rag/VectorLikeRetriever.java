package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class VectorLikeRetriever {
    private final KnowledgeCorpus corpus;

    public VectorLikeRetriever(KnowledgeCorpus corpus) {
        this.corpus = corpus;
    }

    public List<KnowledgeChunk> search(RetrieveRequest request, int topK) {
        Set<String> queryTerms = new HashSet<>(Bm25Retriever.tokenize(expandSynonyms(request.query())));
        return corpus.chunks().stream()
                .filter(c -> request.domainCode() == null || request.domainCode().equals(c.domainCode()))
                .map(c -> c.withScore(jaccard(queryTerms, new HashSet<>(Bm25Retriever.tokenize(expandSynonyms(c.title() + " " + c.content()))))))
                .filter(c -> c.score() > 0)
                .sorted(Comparator.comparingDouble(KnowledgeChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    private String expandSynonyms(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("被拒", "拒绝 风控 RISK_REJECT")
                .replace("支付失败", "支付失败 失败 FAILED")
                .replace("超时", "超时 TIMEOUT CHANNEL_TIMEOUT")
                .replace("扣款", "支付 扣款 渠道");
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
