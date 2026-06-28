package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.core.AgentModels.EvidencePackage;
import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.Turn;
import com.cyc.cyctest.agent.memory.MemoryGraph.MemoryEdge;
import com.cyc.cyctest.agent.memory.MemoryGraph.MemoryNode;

import java.time.Instant;
import java.util.List;

/**
 * ConversationContext 的可序列化快照，用于 Redis 持久化。
 * 所有字段均为 Jackson 可序列化类型，通过 JavaTimeModule 支持 Instant。
 *
 * 注意：旧版本快照 JSON 可能含有 "turns" 字段（已废弃），
 * Jackson 默认忽略未知字段，反序列化不受影响。
 */
public record ConversationSnapshot(
        String sessionId,
        Instant createdAt,
        String title,
        List<Turn> structuredTurns,
        int compressedTurnCount,
        int clarifyCount,
        String pendingClarifyQuestion,
        String currentGoal,
        String summary,
        Instant summaryUpdatedAt,
        SlotState slotState,
        RouteResult currentRoute,
        ExecutionPlan currentPlan,
        EvidencePackage evidencePackage,
        List<MemoryNode> nodes,
        List<MemoryEdge> edges,
        String lastTurnNodeId
) {
}
