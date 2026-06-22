package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;

import java.util.List;

/**
 * 语义检索接口。
 * legacy 模式由 VectorLikeRetriever 实现（Jaccard），
 * spring-ai 模式由 SpringAiVectorRetriever 实现（真实 Embedding + VectorStore）。
 */
public interface SemanticRetriever {
    List<KnowledgeChunk> search(RetrieveRequest request, int topK);
}
