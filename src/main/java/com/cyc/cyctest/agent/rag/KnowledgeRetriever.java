package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;

import java.util.List;

/**
 * 知识检索统一接口。
 * legacy 模式由 HybridKnowledgeRetriever 实现（BM25 + Jaccard），
 * spring-ai 模式由 SpringAiKnowledgeRetriever 实现（BM25 + VectorStore + RRF）。
 */
public interface KnowledgeRetriever {
    List<KnowledgeChunk> retrieve(RetrieveRequest request);
}
