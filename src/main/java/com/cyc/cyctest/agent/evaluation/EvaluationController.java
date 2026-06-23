package com.cyc.cyctest.agent.evaluation;

import org.springframework.web.bind.annotation.*;

/**
 * 评估 API。
 * <p>
 * 提供两个入口：
 * 1. POST /api/eval/judge — 单次 LLM-as-Judge（给定 question/answer/context 打分）
 * 2. POST /api/eval/run  — 批量跑黄金集，返回完整 EvalReport
 */
@RestController
@RequestMapping("/api/eval")
public class EvaluationController {

    private final EvaluationService evaluationService;
    private final EvalRunner evalRunner;

    public EvaluationController(EvaluationService evaluationService, EvalRunner evalRunner) {
        this.evaluationService = evaluationService;
        this.evalRunner = evalRunner;
    }

    /**
     * 单次 LLM-as-Judge 评估（RAGAS 四指标）。
     * 典型用途：在线抽样评估生产流量、A/B 实验效果对比。
     */
    @PostMapping("/judge")
    public EvaluationService.EvalResult judge(@RequestBody EvalRequest request) {
        if (request.question() == null || request.answer() == null) {
            throw new IllegalArgumentException("question 和 answer 不能为空");
        }
        return evaluationService.evaluate(request.question(), request.answer(), request.context());
    }

    /**
     * 批量跑黄金集，返回完整 EvalReport。
     * 包含路由准确率、工具覆盖率、Faithfulness、Relevance 等全维度指标。
     * LLM 不可用时 LLM-as-Judge 自动跳过，规则评估仍正常运行。
     */
    @PostMapping("/run")
    public EvalReport run() {
        return evalRunner.run();
    }

    /**
     * 只返回评估摘要（不含全量用例详情，响应更轻量）。
     */
    @PostMapping("/run/summary")
    public EvalSummary runSummary() {
        EvalReport report = evalRunner.run();
        return new EvalSummary(
                report.totalCases(), report.passedCases(), report.passRate(),
                report.routeAccuracy(), report.avgToolRecallRate(),
                report.avgFaithfulness(), report.avgRelevance(),
                report.failures().stream().map(EvalCaseResult::caseId).toList()
        );
    }

    public record EvalRequest(String question, String answer, String context) {}

    public record EvalSummary(
            int totalCases,
            int passedCases,
            double passRate,
            double routeAccuracy,
            double avgToolRecallRate,
            double avgFaithfulness,
            double avgRelevance,
            java.util.List<String> failedCaseIds
    ) {}
}
