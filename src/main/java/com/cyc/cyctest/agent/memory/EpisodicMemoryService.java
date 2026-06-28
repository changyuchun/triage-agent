package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.core.AgentModels.AgentRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * L4 情节记忆（Episodic Memory）服务。
 * <p>
 * 在每次完整对话结束（SYNTHESIZE 阶段）后，将关键信息（目标、路由、结论）
 * 以 Embedding 向量存入 Redis VectorStore，metadata 打上 type=episodic 标签。
 * <p>
 * 在新会话开始时，可通过语义搜索召回最相关的历史情节，
 * 为 LLM 提供长期上下文（超越单会话的记忆能力）。
 */
@Service
public class EpisodicMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryService.class);
    private static final int MAX_CONTENT_LEN = 300;

    private final VectorStore vectorStore;

    @Value("${agent.episodic.enabled:true}")
    private boolean enabled;

    public EpisodicMemoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 将当次对话的关键信息记录为情节记忆。
     * 仅当有最终答案且路由明确时才记录（避免噪声）。
     */
    public void recordEpisode(String sessionId, AgentRunContext ctx) {
        if (!enabled) return;
        if (ctx.finalAnswer() == null || ctx.finalAnswer().isBlank()) return;
        if (ctx.route() == null) return;

        try {
            String content = buildEpisodeContent(sessionId, ctx);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "episodic");
            metadata.put("sessionId", sessionId);
            metadata.put("domainCode", ctx.route().domainCode() != null ? ctx.route().domainCode() : "");
            metadata.put("subDomainCode", ctx.route().subDomainCode() != null ? ctx.route().subDomainCode() : "");
            metadata.put("title", "Episode: " + abbreviate(ctx.userText(), 30));
            metadata.put("source", "episodic-memory");

            vectorStore.add(List.of(new Document(content, metadata)));
            log.debug("会话 {} 情节记忆已记录: {}", sessionId, abbreviate(ctx.userText(), 50));
        } catch (Exception e) {
            log.warn("情节记忆写入 VectorStore 失败: {}", e.getMessage());
        }
    }

    /**
     * 按语义搜索相关历史情节，用于为新问题补充长期上下文。
     */
    public List<String> recallRelevant(String query, int topK) {
        if (!enabled) return List.of();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression("type == 'episodic'")
                    .build();
            return vectorStore.similaritySearch(request).stream()
                    .map(Document::getText)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("情节记忆召回失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildEpisodeContent(String sessionId, AgentRunContext ctx) {
        return String.format(
                "会话目标: %s\n路由: %s/%s\n关键槽位: %s\n结论摘要: %s",
                effectiveGoal(ctx),
                ctx.route().domainCode(),
                ctx.route().subDomainCode(),
                ctx.slots(),
                abbreviate(ctx.finalAnswer(), MAX_CONTENT_LEN)
        );
    }

    /**
     * 优先用 ClarifyService 推断的完整意图（含上下文），兜底用当轮原始输入。
     * userGoal 由 LLM 在 EXTRACT 阶段推断，语义更完整，向量匹配更准。
     */
    public static String effectiveGoal(AgentRunContext ctx) {
        return (ctx.clarify() != null && ctx.clarify().userGoal() != null && !ctx.clarify().userGoal().isBlank())
                ? ctx.clarify().userGoal()
                : ctx.userText();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
