package com.cyc.cyctest.agent.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理 REST API - 对标 Letta（MemGPT）的 Memory Admin 接口规范。
 * <p>
 * Letta 核心 API 设计：
 * - GET /v1/agents/{agent_id}/memory            → 查看当前记忆状态
 * - GET /v1/agents/{agent_id}/archival-memory   → 翻页查询归档记忆
 * - POST /v1/agents/{agent_id}/archival-memory  → 管理员写入归档记忆
 * - GET /v1/agents/{agent_id}/messages          → 查询 Recall Memory（消息历史）
 * <p>
 * 本项目对应实现：
 * - GET /api/memory/layers/{sessionId}    → 5层记忆快照（L1~L5）
 * - GET /api/memory/archival              → 查询 VectorStore 归档记忆
 * - POST /api/memory/archival             → 手动写入归档记忆
 * - GET /api/memory/sessions              → 所有会话列表
 * - DELETE /api/memory/sessions/{id}      → 删除指定会话
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryAdminController {

    private final MemoryStore memoryStore;
    private final SessionRepository sessionRepository;
    private final VectorStore vectorStore;

    public MemoryAdminController(MemoryStore memoryStore,
                                 SessionRepository sessionRepository,
                                 VectorStore vectorStore) {
        this.memoryStore = memoryStore;
        this.sessionRepository = sessionRepository;
        this.vectorStore = vectorStore;
    }

    /** 查看指定会话的5层记忆快照（对标 Letta GET /memory） */
    @GetMapping("/layers/{sessionId}")
    public LayeredMemorySnapshot layers(@PathVariable String sessionId) {
        return memoryStore.layeredSnapshot(sessionId);
    }

    /** 查询 VectorStore 归档记忆（语义搜索） */
    @GetMapping("/archival")
    public List<ArchivalEntry> searchArchival(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK) {
        var filterBuilder = new FilterExpressionBuilder();
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterBuilder.eq("type", "archival").build())
                .build();
        return vectorStore.similaritySearch(req).stream()
                .map(doc -> new ArchivalEntry(
                        doc.getId(),
                        doc.getText(),
                        (String) doc.getMetadata().getOrDefault("label", ""),
                        (String) doc.getMetadata().getOrDefault("sessionId", ""),
                        (String) doc.getMetadata().getOrDefault("createdAt", ""),
                        (String) doc.getMetadata().getOrDefault("source", "")
                ))
                .toList();
    }

    /** 手动写入归档记忆（管理员接口） */
    @PostMapping("/archival")
    public Map<String, Object> insertArchival(@RequestBody ArchivalInsertRequest req) {
        String id = "archival-admin-" + System.currentTimeMillis();
        vectorStore.add(List.of(new Document(id, req.content(), Map.of(
                "type", "archival",
                "label", req.label() != null ? req.label() : "admin",
                "sessionId", req.sessionId() != null ? req.sessionId() : "admin",
                "source", "admin-api",
                "createdAt", java.time.Instant.now().toString()
        ))));
        return Map.of("success", true, "id", id);
    }

    /** 所有会话列表 */
    @GetMapping("/sessions")
    public List<MemoryStore.SessionSummary> sessions() {
        return memoryStore.listSessions();
    }

    /** 记忆图（节点和边） */
    @GetMapping("/graph/{sessionId}")
    public MemoryGraph graph(@PathVariable String sessionId) {
        return memoryStore.graph(sessionId);
    }

    public record ArchivalEntry(String id, String content, String label,
                                String sessionId, String createdAt, String source) {}

    public record ArchivalInsertRequest(String content, String label, String sessionId) {}
}
