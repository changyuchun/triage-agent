package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * L3 摘要记忆（Summary Memory）压缩服务。
 * <p>
 * 当 ConversationContext 的未压缩轮次 ≥ 6 时，调用 LLM 将历史对话
 * 压缩为一段简洁摘要，并写回 ConversationContext.summary。
 * <p>
 * 如果 LLM 调用失败，则回退到模板化压缩（不影响主流程）。
 */
@Service
public class MemoryCompressionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressionService.class);

    private static final String SYSTEM_PROMPT = """
            你是会话记忆压缩模块。请将以下对话历史压缩为一段简洁摘要。
            保留：用户目标、已确认的关键信息（订单号/错误码/环境）、重要决策、未解决的问题。
            丢弃：重复信息、中间过程、已完成的澄清轮次。
            格式要求：纯文本，200字以内，中文。
            """;

    private final LlmClient llmClient;

    public MemoryCompressionService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 如果需要压缩则调用 LLM 生成摘要，否则跳过。
     */
    public void compressIfNeeded(ConversationContext memory) {
        if (!memory.needsCompression()) {
            return;
        }
        if (!llmClient.available()) {
            memory.refreshSummaryIfNeeded();
            return;
        }
        try {
            String turns = memory.turnsForCompression();
            String slotInfo = "当前槽位: " + memory.slotState();
            String userPrompt = slotInfo + "\n\n对话历史:\n" + turns;
            String summary = llmClient.complete(SYSTEM_PROMPT, userPrompt);
            memory.updateSummary(summary);
            log.debug("会话 {} 记忆压缩完成，摘要长度: {}", memory.sessionId(), summary.length());
        } catch (Exception e) {
            log.warn("LLM 记忆压缩失败，回退到模板压缩: {}", e.getMessage());
            memory.refreshSummaryIfNeeded();
        }
    }
}
