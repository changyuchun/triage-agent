package com.cyc.cyctest.agent.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvalReport(
        Instant runAt,
        int totalCases,
        int passedCases,
        double passRate,
        // 组件级指标（规则打分，快速）
        double routeAccuracy,
        double avgToolRecallRate,
        double avgEvidenceScore,
        double avgFactCoverageRate,
        // 答案质量指标（LLM-as-Judge，0-1，LLM 不可用时为 0）
        double avgFaithfulness,
        double avgRelevance,
        double avgCompleteness,
        // 按 tag 分组（定位哪类问题最差）
        Map<String, TagSummary> byTag,
        // 失败案例（供改进定位）
        List<EvalCaseResult> failures,
        List<EvalCaseResult> allResults
) {
    public record TagSummary(
            int total,
            int passed,
            double routeAccuracy,
            double avgFaithfulness
    ) {}
}
