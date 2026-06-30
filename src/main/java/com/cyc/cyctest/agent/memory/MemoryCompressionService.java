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
            你是会话记忆压缩模块。
            若提供了【历史摘要】，将【新增对话】融合进去，输出更新后的完整摘要；
            若没有【历史摘要】，直接压缩【对话历史】。
            保留：用户目标、已确认的关键信息（订单号/错误码/环境）、重要决策、未解决的问题。
            丢弃：重复信息、中间过程、已完成的澄清轮次。
            格式要求：纯文本，300字以内，中文。
            """;

    private final LlmClient llmClient;

    public MemoryCompressionService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 归档前强制压缩：不管 needsCompression() 结果，把全部剩余 turns 融入 summary。
     * 调用后 structuredTurns 只保留最近 retainAfterCompress 条（updateSummary 的既有逻辑）。
     */
    public void forceCompressAll(ConversationContext memory) {
        String allTurns = memory.recentTurns();
        if (allTurns.isBlank()) return;
        if (!llmClient.available()) {
            memory.refreshSummaryIfNeeded();
            return;
        }
        String userPrompt = buildPrompt("当前槽位: " + memory.slotState(), memory.summary(), allTurns);
        try {
            String summary = llmClient.complete(SYSTEM_PROMPT, userPrompt);
            memory.updateSummary(summary);
            log.debug("会话 {} 归档前最终压缩完成", memory.sessionId());
        } catch (Exception e) {
            log.warn("归档前最终压缩失败，回退到模板: {}", e.getMessage());
            memory.refreshSummaryIfNeeded();
        }
    }

    private String buildPrompt(String slotInfo, String existingSummary, String turns) {
        StringBuilder sb = new StringBuilder();
        sb.append(slotInfo).append("\n\n");
        boolean hasSummary = existingSummary != null
                && !existingSummary.isBlank()
                && !existingSummary.equals("暂无摘要");
        if (hasSummary) {
            sb.append("【历史摘要】\n").append(existingSummary).append("\n\n");
            sb.append("【新增对话】\n");
        } else {
            sb.append("【对话历史】\n");
        }
        sb.append(turns);
        return sb.toString();
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
            String existingSummary = memory.summary();
            String userPrompt = buildPrompt(slotInfo, existingSummary, turns);
            String summary = llmClient.complete(SYSTEM_PROMPT, userPrompt);
            memory.updateSummary(summary);
            log.debug("会话 {} 记忆压缩完成，摘要长度: {}", memory.sessionId(), summary.length());
        } catch (Exception e) {
            log.warn("LLM 记忆压缩失败，回退到模板压缩: {}", e.getMessage());
            memory.refreshSummaryIfNeeded();
        }
    }
}
