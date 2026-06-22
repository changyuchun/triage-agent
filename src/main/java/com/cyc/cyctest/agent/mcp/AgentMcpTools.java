package com.cyc.cyctest.agent.mcp;

import com.cyc.cyctest.agent.core.AgentModels;
import com.cyc.cyctest.agent.core.AgentRuntime;
import com.cyc.cyctest.agent.memory.EpisodicMemoryService;
import com.cyc.cyctest.agent.rag.KnowledgeModels;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import com.cyc.cyctest.agent.skill.skills.LogQuerySkill;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通过 Spring AI {@code @Tool} 注解将 Agent 核心能力暴露为 MCP 工具。
 * <p>
 * 当 spring.ai.mcp.server.enabled=true 时，这些工具会自动注册到 MCP Server，
 * 外部 MCP Client（如 Claude Desktop、Cursor 等）可直接调用。
 * <p>
 * 同时，ChatClient 可以通过 .tools(agentMcpTools) 将这些工具提供给 LLM 进行自动调用。
 */
@Component
public class AgentMcpTools {

    private final AgentRuntime agentRuntime;
    private final KnowledgeRetriever knowledgeRetriever;
    private final EpisodicMemoryService episodicMemoryService;
    private final LogQuerySkill logQuerySkill;

    public AgentMcpTools(AgentRuntime agentRuntime,
                          KnowledgeRetriever knowledgeRetriever,
                          EpisodicMemoryService episodicMemoryService,
                          LogQuerySkill logQuerySkill) {
        this.agentRuntime = agentRuntime;
        this.knowledgeRetriever = knowledgeRetriever;
        this.episodicMemoryService = episodicMemoryService;
        this.logQuerySkill = logQuerySkill;
    }

    /**
     * 向 AI Agent 发送消息并获取回答。
     * Agent 会自动进行槽位提取、领域路由、工具调用、RAG 检索和答案合成。
     */
    @Tool(description = "向智能客服 Agent 发送消息，获得基于知识库和工具的智能回答。" +
            "Agent 支持多轮对话，需要时会追问澄清。")
    public String chat(
            @ToolParam(description = "会话 ID，相同 ID 的对话共享上下文") String sessionId,
            @ToolParam(description = "用户消息内容") String message) {
        AgentModels.ChatResponse resp = agentRuntime.run(
                sessionId != null && !sessionId.isBlank() ? sessionId : "mcp-default",
                message);
        if (resp.waitingUserInput()) {
            return "需要补充信息：" + resp.clarifyQuestion();
        }
        return resp.answer() != null ? resp.answer() : "处理完成，当前状态：" + resp.state();
    }

    /**
     * 在知识库中进行语义搜索，返回最相关的知识片段。
     */
    @Tool(description = "在知识库中语义搜索，返回与查询最相关的知识片段列表。" +
            "支持按领域过滤（payment/trade/marketing/general）。")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询文本") String query,
            @ToolParam(description = "领域过滤（可选）：payment/trade/marketing/general，留空表示全局搜索") String domain) {
        List<KnowledgeModels.KnowledgeChunk> chunks = knowledgeRetriever.retrieve(
                new KnowledgeModels.RetrieveRequest(
                        query,
                        domain != null && !domain.isBlank() ? domain : null,
                        null, Map.of(), 5));
        if (chunks.isEmpty()) {
            return "知识库中未找到相关内容";
        }
        return chunks.stream()
                .map(c -> String.format("【%s】（相关度 %.0f%%）\n%s",
                        c.title(), c.score() * 100, c.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 召回与当前问题最相关的历史情节记忆，用于为 LLM 提供长期上下文。
     */
    @Tool(description = "从历史情节记忆中语义搜索与当前问题最相关的历史对话片段，" +
            "帮助 AI 回忆过去的对话经验。")
    public String recallEpisodes(
            @ToolParam(description = "当前问题或查询") String query) {
        List<String> episodes = episodicMemoryService.recallRelevant(query, 3);
        if (episodes.isEmpty()) {
            return "暂无相关历史情节记忆";
        }
        return episodes.stream()
                .map(e -> "历史片段：\n" + e)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 查询应用日志：按关键词、时间段、日志级别过滤应用运行日志。
     * 生产中对接 ELK/SkyWalking/阿里云 SLS，当前为 Mock 实现。
     */
    @Tool(description = "查询应用运行日志，支持按 traceId/payOrderId/错误码等关键词过滤，" +
            "适用于线上问题排查和根因定位。")
    public String queryLogs(
            @ToolParam(description = "查询关键词，如 traceId、payOrderId、errorCode") String keyword,
            @ToolParam(description = "时间范围：15m/1h/6h/1d，默认 1h") String timeRange,
            @ToolParam(description = "日志级别：ERROR/WARN/INFO，默认 ERROR") String level) {
        return logQuerySkill.queryLogsAsTool(keyword, timeRange, level);
    }
}
