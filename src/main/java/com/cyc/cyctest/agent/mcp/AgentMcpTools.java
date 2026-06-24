package com.cyc.cyctest.agent.mcp;

import com.cyc.cyctest.agent.core.AgentModels;
import com.cyc.cyctest.agent.core.AgentRuntime;
import com.cyc.cyctest.agent.memory.EpisodicMemoryService;
import com.cyc.cyctest.agent.rag.KnowledgeModels;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台级 MCP 工具集：将 Agent 核心基础设施能力暴露为 MCP 工具。
 * <p>
 * 领域业务工具（支付/交易/日志/营销查询）由 {@link ToolCallbackAdapter} 统一注册，不在此重复。
 */
@Component
public class AgentMcpTools {

    private final AgentRuntime agentRuntime;
    private final KnowledgeRetriever knowledgeRetriever;
    private final EpisodicMemoryService episodicMemoryService;
    private final SkillRegistry skillRegistry;

    public AgentMcpTools(AgentRuntime agentRuntime,
                         KnowledgeRetriever knowledgeRetriever,
                         EpisodicMemoryService episodicMemoryService,
                         SkillRegistry skillRegistry) {
        this.agentRuntime = agentRuntime;
        this.knowledgeRetriever = knowledgeRetriever;
        this.episodicMemoryService = episodicMemoryService;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 向 Agent 发送消息，触发完整的 Plan-and-Execute 流程。
     * 支持多轮对话，相同 sessionId 共享槽位和对话历史。
     */
    @Tool(description = "向智能答疑 Agent 发送消息。Agent 会自动完成意图理解、领域路由、" +
            "工具调用、知识检索和答案合成。支持多轮对话，相同 sessionId 的对话共享上下文。")
    public String chat(
            @ToolParam(description = "会话 ID，相同 ID 的对话共享槽位和历史上下文") String sessionId,
            @ToolParam(description = "用户消息内容") String message) {
        AgentModels.ChatResponse resp = agentRuntime.run(
                sessionId != null && !sessionId.isBlank() ? sessionId : "mcp-default",
                message);
        if (resp.waitingUserInput()) {
            return "需要补充信息：" + resp.clarifyQuestion();
        }
        return resp.answer() != null ? resp.answer() : "处理完成，状态：" + resp.state();
    }

    /**
     * 在知识库中进行语义搜索，返回最相关的知识片段。
     * 底层走 Hybrid Search（BM25 + Vector + RRF），比全文搜索效果更好。
     */
    @Tool(description = "在知识库中语义搜索，返回与查询最相关的知识片段。" +
            "底层使用 BM25 + 向量检索 + RRF 融合，支持按领域过滤。")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询文本") String query,
            @ToolParam(description = "领域过滤（可选）：payment/trade/marketing/general，留空为全局搜索") String domain) {
        List<KnowledgeModels.KnowledgeChunk> chunks = knowledgeRetriever.retrieve(
                new KnowledgeModels.RetrieveRequest(
                        query,
                        domain != null && !domain.isBlank() ? domain : null,
                        null, Map.of(), 5));
        if (chunks.isEmpty()) {
            return "知识库中未找到关于「" + query + "」的相关内容";
        }
        return chunks.stream()
                .map(c -> String.format("【%s】（相关度 %.0f%%）\n%s",
                        c.title(), c.score() * 100, c.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 召回与当前问题最相关的历史情节记忆（L4 记忆层）。
     * 通过 Embedding 向量检索历史对话，跨会话提供经验参考。
     */
    @Tool(description = "从历史情节记忆中语义检索与当前问题最相关的历史对话片段。" +
            "用于跨会话经验复用，避免重复排查同类问题。")
    public String recallEpisodes(
            @ToolParam(description = "当前问题或查询关键词") String query) {
        List<String> episodes = episodicMemoryService.recallRelevant(query, 3);
        if (episodes.isEmpty()) {
            return "暂无与「" + query + "」相关的历史情节记忆";
        }
        return episodes.stream()
                .map(e -> "历史片段：\n" + e)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 列出当前 Agent 所有可用 Skill 的能力清单。
     * 供外部 MCP 客户端或用户了解 Agent 的工具能力边界。
     */
    @Tool(description = "列出当前 Agent 所有注册的工具和 Skill，包括工具描述和激活条件。")
    public String listSkills() {
        var tools = skillRegistry.allTools();
        if (tools.isEmpty()) {
            return "当前没有注册任何工具";
        }
        StringBuilder sb = new StringBuilder("当前注册工具（共 ")
                .append(tools.size()).append(" 个）：\n\n");
        for (var tool : tools.values()) {
            var def = tool.definition();
            sb.append(String.format("• [%s] %s\n  描述: %s\n  必填参数: %s%n",
                    def.code(), def.name(), def.description(), def.requiredFields()));
        }
        return sb.toString();
    }
}
