package com.cyc.cyctest.agent.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 会话内存门面（Facade），屏蔽底层存储实现。
 * 实际存储由 {@link SessionRepository} 负责：
 * - 开发：InMemorySessionRepository（默认）
 * - 生产：RedisSessionRepository（agent.memory.store=redis）
 */
@Component
public class MemoryStore {

    private final SessionRepository sessionRepository;

    public MemoryStore(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public ConversationContext load(String sessionId) {
        return sessionRepository.loadOrCreate(sessionId);
    }

    /** 将会话状态持久化（每次 AgentRuntime.run() 结束后调用）*/
    public void save(ConversationContext context) {
        sessionRepository.save(context);
    }

    public List<SessionSummary> listSessions() {
        return sessionRepository.listSessions();
    }

    public MemoryGraph graph(String sessionId) {
        return load(sessionId).graph();
    }

    public LayeredMemorySnapshot layeredSnapshot(String sessionId) {
        return load(sessionId).layeredSnapshot();
    }

    public record SessionSummary(
            String sessionId,
            String title,
            Instant createdAt,
            int clarifyCount,
            String pendingClarifyQuestion
    ) {
    }
}
