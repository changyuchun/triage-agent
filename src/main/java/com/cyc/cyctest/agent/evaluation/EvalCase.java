package com.cyc.cyctest.agent.evaluation;

import java.util.List;
import java.util.Map;

public record EvalCase(
        String caseId,
        String userText,
        Map<String, String> presetSlots,
        Expected expected,
        List<String> tags
) {
    public record Expected(
            String domainCode,
            List<String> toolCodes,
            double minQualityScore,
            List<String> mustContainFacts
    ) {
        public Expected {
            if (toolCodes == null) toolCodes = List.of();
            if (mustContainFacts == null) mustContainFacts = List.of();
            if (minQualityScore <= 0) minQualityScore = 0.3;
        }
    }

    public List<String> tags() {
        return tags != null ? tags : List.of();
    }

    public Map<String, String> presetSlots() {
        return presetSlots != null ? presetSlots : Map.of();
    }
}
