package com.cyc.cyctest.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 归档记忆工具（Archival Memory）- 对标 Letta/MemGPT 的 Memory 管理工具。
 * <p>
 * Letta 核心创新：将记忆管理本身变成 LLM 可调用的工具，让 AI 自主决定：
 * - 哪些信息值得长期保存（archival_memory_insert）
 * - 什么时候主动查询历史（archival_memory_search）
 * <p>
 * 记忆分类（对比 Letta 论文）：
 * - In-Context Memory：当前 Prompt 窗口内（我们的 L1 Working Memory）
 * - Recall Memory：近期对话检索（L2/L3 Redis Session）
 * - Archival Memory：无限容量向量存储（L4/L5 VectorStore，本类负责）
 * <p>
 * 与知识库（L5）的区别：
 * - 知识库：用户/管理员写入的领域知识
 * - 归档记忆：AI 自主判断并写入的个人化长期记忆
 * <p>
 * 同时暴露为 Spring AI MCP Server Tool，可供 Claude Desktop 等外部 Host 调用。
 */
@Component
public class ArchivalMemoryTool {

    private static final Logger log = LoggerFactory.getLogger(ArchivalMemoryTool.class);
    private static final String ARCHIVAL_TYPE = "archival";
    private static final int SEARCH_TOP_K = 5;

    private final VectorStore vectorStore;

    public ArchivalMemoryTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 写入归档记忆（Archival Memory Insert）。
     * <p>
     * LLM 在对话中发现重要信息（用户偏好、关键决策、事实数据等）时，
     * 主动调用此工具保存到 VectorStore，下次会话可通过语义检索召回。
     */
    @Tool(description = "将重要信息永久保存到归档记忆库。当你发现用户有重要偏好、"
            + "关键事实或需要跨会话记住的信息时，主动调用此工具存储。")
    public String archivalMemoryInsert(
            @ToolParam(description = "要保存的记忆内容（100-500字）") String content,
            @ToolParam(description = "记忆标签，如 user_preference / key_fact / decision") String label,
            @ToolParam(description = "关联的 sessionId（当前对话标识）") String sessionId) {
        try {
            String memoryId = "archival-" + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> metadata = Map.of(
                    "type", ARCHIVAL_TYPE,
                    "label", label != null ? label : "general",
                    "sessionId", sessionId != null ? sessionId : "unknown",
                    "createdAt", Instant.now().toString(),
                    "source", "llm-self-archival"
            );
            vectorStore.add(List.of(new Document(memoryId, content, metadata)));
            log.info("[ArchivalMemory] 写入记忆 id={} label={} session={}", memoryId, label, sessionId);
            return "记忆已保存，id=" + memoryId;
        } catch (Exception e) {
            log.error("[ArchivalMemory] 写入失败: {}", e.getMessage());
            return "记忆保存失败: " + e.getMessage();
        }
    }

    /**
     * 检索归档记忆（Archival Memory Search）。
     * <p>
     * 语义检索：输入关键词或描述，从 VectorStore 中召回最相关的历史记忆。
     * LLM 在回答需要历史背景的问题时，主动调用此工具查找相关记忆。
     */
    @Tool(description = "在归档记忆库中语义搜索历史记忆。当需要回忆用户偏好、"
            + "过去的决策或历史事实时调用。")
    public String archivalMemorySearch(
            @ToolParam(description = "搜索查询，描述你想找的记忆内容") String query) {
        try {
            var filterBuilder = new FilterExpressionBuilder();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(SEARCH_TOP_K)
                    .filterExpression(filterBuilder.eq("type", ARCHIVAL_TYPE).build())
                    .build();
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            if (docs.isEmpty()) {
                return "未找到相关归档记忆。";
            }
            StringBuilder sb = new StringBuilder("找到 ").append(docs.size()).append(" 条相关记忆：\n\n");
            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                sb.append("[").append(i + 1).append("] ");
                sb.append("标签: ").append(doc.getMetadata().getOrDefault("label", "?")).append("\n");
                sb.append("内容: ").append(doc.getText()).append("\n");
                sb.append("时间: ").append(doc.getMetadata().getOrDefault("createdAt", "?")).append("\n\n");
            }
            log.info("[ArchivalMemory] 检索到 {} 条记忆，查询: {}", docs.size(), query);
            return sb.toString();
        } catch (Exception e) {
            log.error("[ArchivalMemory] 检索失败: {}", e.getMessage());
            return "记忆检索失败: " + e.getMessage();
        }
    }
}
