package com.cyc.cyctest.agent.evaluation;

import java.util.List;

public record EvalCaseResult(
        String caseId,
        String userText,
        List<String> tags,
        // Layer 1：规则评估
        boolean routeCorrect,
        double toolRecallRate,
        double avgEvidenceScore,
        double factCoverageRate,
        // Layer 2：LLM-as-Judge（LLM 不可用时为 0，不影响 passed 判定）
        double faithfulness,
        double relevance,
        double completeness,
        // 结果
        boolean passed,
        String failReason,
        // 调试信息
        String actualDomainCode,
        List<String> actualToolCodes
) {
    public static EvalCaseResult failed(String caseId, String userText, List<String> tags, String reason) {
        return new EvalCaseResult(caseId, userText, tags,
                false, 0, 0, 0, 0, 0, 0,
                false, reason, null, List.of());
    }
}
