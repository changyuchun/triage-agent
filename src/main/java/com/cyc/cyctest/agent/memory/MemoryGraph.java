package com.cyc.cyctest.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MemoryGraph(
        String sessionId,
        String title,
        List<MemoryNode> nodes,
        List<MemoryEdge> edges
) {
    public record MemoryNode(
            String id,
            String type,
            String label,
            Map<String, Object> properties,
            Instant createdAt
    ) {
    }

    public record MemoryEdge(
            String from,
            String to,
            String type,
            Map<String, Object> properties,
            Instant createdAt
    ) {
    }
}
