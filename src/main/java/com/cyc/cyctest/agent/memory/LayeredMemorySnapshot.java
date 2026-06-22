package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.core.AgentModels.Evidence;
import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LayeredMemorySnapshot(
        String sessionId,
        ConversationMemory conversationMemory,
        WorkingMemory workingMemory,
        EvidenceMemory evidenceMemory,
        SummaryMemory summaryMemory,
        SystemMemory systemMemory
) {
    public record ConversationMemory(
            List<Turn> recentTurns,
            int totalTurns
    ) {
    }

    public record Turn(
            String role,
            String content,
            Instant createdAt
    ) {
    }

    public record WorkingMemory(
            SlotState slots,
            RouteResult currentRoute,
            ExecutionPlan currentPlan,
            String pendingClarifyQuestion,
            int clarifyCount,
            String currentGoal
    ) {
    }

    public record EvidenceMemory(
            List<Evidence> evidence,
            double qualityScore
    ) {
    }

    public record SummaryMemory(
            String summary,
            Instant updatedAt,
            int compressedTurnCount
    ) {
    }

    public record SystemMemory(
            String userId,
            List<String> enabledDomains,
            Map<String, Object> runtimePolicy
    ) {
    }
}
