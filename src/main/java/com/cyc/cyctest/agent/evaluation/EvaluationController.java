package com.cyc.cyctest.agent.evaluation;

import org.springframework.web.bind.annotation.*;

/**
 * RAG 评估 API。
 * <p>
 * 核心能力：LLM-as-Judge + RAGAS 指标（Faithfulness / Relevance / Completeness）。
 * 面试场景：展示对 AI 系统评估体系的理解，这是生产 AI 应用的核心质量保障手段。
 */
@RestController
@RequestMapping("/api/eval")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * 使用 LLM-as-Judge 评估一次 AI 回答。
     * <p>
     * 典型用途：
     * 1. 上线前的批量回归评估（Batch API 可降低 50% 成本）
     * 2. 在线质量监控（抽样评估生产流量）
     * 3. A/B 实验效果对比
     */
    @PostMapping("/judge")
    public EvaluationService.EvalResult judge(@RequestBody EvalRequest request) {
        if (request.question() == null || request.answer() == null) {
            throw new IllegalArgumentException("question 和 answer 不能为空");
        }
        return evaluationService.evaluate(request.question(), request.answer(), request.context());
    }

    public record EvalRequest(String question, String answer, String context) {}
}
