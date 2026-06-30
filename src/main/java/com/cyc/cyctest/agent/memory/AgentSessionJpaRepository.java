package com.cyc.cyctest.agent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AgentSessionJpaRepository extends JpaRepository<AgentSessionEntity, String> {

    List<AgentSessionEntity> findByUserIdOrderByLastActiveAtDesc(String userId);

    /** 找出最近活跃时间早于 threshold 且尚未归档的 sessionId */
    @Query("SELECT e.sessionId FROM AgentSessionEntity e WHERE e.lastActiveAt < :threshold AND e.archivedAt IS NULL")
    List<String> findOldActiveSessionIds(Instant threshold);
}
