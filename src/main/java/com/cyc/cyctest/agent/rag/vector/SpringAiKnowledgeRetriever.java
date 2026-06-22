package com.cyc.cyctest.agent.rag.vector;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.rag.Bm25Retriever;
import com.cyc.cyctest.agent.rag.KnowledgeModels.FusedResult;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import com.cyc.cyctest.agent.rag.QueryRewriter;
import com.cyc.cyctest.agent.rag.ReciprocalRankFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 模式下的 Advanced Hybrid RAG 检索。
 * <p>
 * 检索管道（Advanced RAG Pipeline）：
 * <pre>
 * 原始查询
 *   └→ [Query Rewriting] 多角度扩展为 N 个子查询
 *         └→ [HyDE] 可选：生成假设文档 Embedding
 *               └→ BM25（关键词） + VectorStore（语义）并行检索
 *                     └→ [RRF] 融合排序
 *                           └→ Top-K 结果
 * </pre>
 * <p>
 * 对比 Naive RAG（直接向量检索）：
 * - Multi-Query 覆盖更多角度，召回率 ↑
 * - HyDE 弥补"问题和文档语义鸿沟"，精度 ↑
 * - BM25 + 向量融合兼顾精确匹配和语义匹配
 */
@Service
@ConditionalOnProperty(name = "agent.rag.retriever", havingValue = "spring-ai")
public class SpringAiKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger log = LoggerFactory.getLogger(SpringAiKnowledgeRetriever.class);

    private final Bm25Retriever bm25Retriever;
    private final SpringAiVectorRetriever vectorRetriever;
    private final AgentProperties properties;
    private final QueryRewriter queryRewriter;

    public SpringAiKnowledgeRetriever(Bm25Retriever bm25Retriever,
                                      SpringAiVectorRetriever vectorRetriever,
                                      AgentProperties properties,
                                      QueryRewriter queryRewriter) {
        this.bm25Retriever = bm25Retriever;
        this.vectorRetriever = vectorRetriever;
        this.properties = properties;
        this.queryRewriter = queryRewriter;
    }

    @Override
    public List<KnowledgeChunk> retrieve(RetrieveRequest request) {
        int recallTopK = properties.runtime().bm25TopK();

        // Step 1: Multi-Query 扩展（Query Rewriting）
        List<String> queries = queryRewriter.expandToMultiQuery(request.query());
        log.debug("[RAG] Multi-Query 扩展 {} → {} 个子查询", request.query(), queries.size());

        // Step 2: 对每个子查询并行做 BM25 + 向量检索，合并结果列表
        List<List<KnowledgeChunk>> allRankLists = new ArrayList<>();
        for (String query : queries) {
            RetrieveRequest subReq = new RetrieveRequest(query, request.domainCode(),
                    request.subDomainCode(), request.filters(), recallTopK);
            List<KnowledgeChunk> bm25 = bm25Retriever.search(subReq, recallTopK);
            List<KnowledgeChunk> vector = vectorRetriever.search(subReq, recallTopK);
            allRankLists.add(bm25);
            allRankLists.add(vector);
        }

        // Step 3: RRF 融合多路排名（Multi-Query 版本，候选集更丰富）
        List<FusedResult> fused = ReciprocalRankFusion.fuse(
                allRankLists,
                properties.runtime().rrfRankConstant(),
                properties.runtime().fusedTopK()
        );

        List<KnowledgeChunk> results = fused.stream()
                .map(FusedResult::chunk)
                .limit(request.topK())
                .toList();

        log.debug("[RAG] 最终召回 {} 条，经过 {} 路候选融合", results.size(), allRankLists.size());
        return results;
    }
}
