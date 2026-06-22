package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.rag.KnowledgeModels.FusedResult;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "agent.rag.retriever", havingValue = "legacy", matchIfMissing = true)
public class HybridKnowledgeRetriever implements KnowledgeRetriever {
    private final Bm25Retriever bm25Retriever;
    private final VectorLikeRetriever vectorRetriever;
    private final AgentProperties properties;

    public HybridKnowledgeRetriever(Bm25Retriever bm25Retriever, VectorLikeRetriever vectorRetriever, AgentProperties properties) {
        this.bm25Retriever = bm25Retriever;
        this.vectorRetriever = vectorRetriever;
        this.properties = properties;
    }

    public List<KnowledgeChunk> retrieve(RetrieveRequest request) {
        int recallTopK = properties.runtime().bm25TopK();
        List<KnowledgeChunk> bm25 = bm25Retriever.search(request, recallTopK);
        List<KnowledgeChunk> vector = vectorRetriever.search(request, recallTopK);
        List<FusedResult> fused = ReciprocalRankFusion.fuse(
                List.of(bm25, vector),
                properties.runtime().rrfRankConstant(),
                properties.runtime().fusedTopK()
        );
        return fused.stream()
                .map(FusedResult::chunk)
                .limit(request.topK())
                .toList();
    }
}
