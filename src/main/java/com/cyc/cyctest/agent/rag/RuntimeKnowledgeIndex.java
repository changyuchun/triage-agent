package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runtime sparse index for user-uploaded chunks.
 * <p>
 * Redis VectorStore is the dense index. BM25 also needs the same uploaded chunks
 * to keep hybrid retrieval truly hybrid after ingestion.
 */
@Component
public class RuntimeKnowledgeIndex {

    private final ConcurrentMap<String, KnowledgeChunk> chunks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> documentChunkIds = new ConcurrentHashMap<>();

    public void upsertDocument(String docId, List<KnowledgeChunk> documentChunks) {
        List<String> chunkIds = documentChunks.stream()
                .map(KnowledgeChunk::chunkId)
                .toList();
        List<String> previousChunkIds = documentChunkIds.put(docId, chunkIds);
        if (previousChunkIds != null) {
            previousChunkIds.forEach(chunks::remove);
        }
        documentChunks.forEach(chunk -> chunks.put(chunk.chunkId(), chunk));
    }

    public List<String> deleteDocument(String docId) {
        List<String> chunkIds = documentChunkIds.remove(docId);
        if (chunkIds == null) {
            return List.of();
        }
        chunkIds.forEach(chunks::remove);
        return chunkIds;
    }

    public List<KnowledgeChunk> chunks() {
        return new ArrayList<>(chunks.values());
    }
}
