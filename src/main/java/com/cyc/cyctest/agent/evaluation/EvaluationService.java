package com.cyc.cyctest.agent.evaluation;

import com.cyc.cyctest.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-Judge 评估服务。
 * <p>
 * RAGAS 框架核心指标：
 * - Faithfulness（忠实度）：回答是否忠于检索到的上下文，有无幻觉
 * - Relevance（相关性）：回答是否与问题相关
 * - Completeness（完整性）：是否完整回答了问题
 * - Overall（综合）：综合评分
 * <p>
 * 用另一个 LLM 实例评判当前 LLM 的回答质量，避免自我评估偏差。
 * 类比：代码 Review 不能自己 Review 自己的 PR。
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private static final String JUDGE_SYSTEM_PROMPT = """
            你是专业的 AI 回答质量评估专家（LLM-as-Judge）。

            评估维度（0-10分，10分最优）：
            1. faithfulness（忠实度）：回答是否基于参考上下文，无幻觉/无中生有
            2. relevance（相关性）：回答是否直接回答了问题
            3. completeness（完整性）：回答是否覆盖了问题的所有关键点
            4. overall（综合）：综合考量以上三点的加权分

            严格以 JSON 格式输出，不要输出任何其他内容：
            {"faithfulness": 数字, "relevance": 数字, "completeness": 数字, "overall": 数字, "reason": "一句话评估理由"}
            """;

    private static final Pattern SCORE_PATTERN = Pattern.compile("\"(\\w+)\"\\s*:\\s*([\\d.]+)");
    private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"");

    private final LlmClient llmClient;

    public EvaluationService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 使用 LLM 对一次 RAG 回答进行评估。
     *
     * @param question 用户问题
     * @param answer   AI 回答
     * @param context  检索到的参考上下文（可为 null）
     */
    public EvalResult evaluate(String question, String answer, String context) {
        String userPrompt = """
                【用户问题】
                %s

                【参考上下文（RAG 检索结果）】
                %s

                【AI 回答】
                %s

                请按格式评估以上 AI 回答的质量。
                """.formatted(question, context != null ? context : "（未提供参考上下文）", answer);

        try {
            String raw = llmClient.complete(JUDGE_SYSTEM_PROMPT, userPrompt);
            EvalResult result = parseResult(raw, question);
            log.info("[Evaluation] LLM-as-Judge 评估完成 overall={} faithfulness={} question={}",
                    result.overall(), result.faithfulness(), truncate(question));
            return result;
        } catch (Exception e) {
            log.error("[Evaluation] 评估失败: {}", e.getMessage());
            return EvalResult.failed(question, e.getMessage());
        }
    }

    private EvalResult parseResult(String raw, String question) {
        double faithfulness = 0, relevance = 0, completeness = 0, overall = 0;
        Matcher m = SCORE_PATTERN.matcher(raw);
        while (m.find()) {
            double v = Double.parseDouble(m.group(2));
            switch (m.group(1)) {
                case "faithfulness" -> faithfulness = v;
                case "relevance" -> relevance = v;
                case "completeness" -> completeness = v;
                case "overall" -> overall = v;
            }
        }
        if (overall == 0 && (faithfulness + relevance + completeness) > 0) {
            overall = (faithfulness + relevance + completeness) / 3.0;
        }
        Matcher rm = REASON_PATTERN.matcher(raw);
        String reason = rm.find() ? rm.group(1) : "解析失败";
        return new EvalResult(question, faithfulness, relevance, completeness, overall, reason, true, raw);
    }

    private String truncate(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    public record EvalResult(
            String question,
            double faithfulness,
            double relevance,
            double completeness,
            double overall,
            String reason,
            boolean success,
            String rawLlmOutput
    ) {
        static EvalResult failed(String question, String error) {
            return new EvalResult(question, 0, 0, 0, 0, error, false, null);
        }
    }
}
