package com.cyc.cyctest.agent.memory;

import java.util.List;

/**
 * 会话存储端口（Port/Adapter 模式）。
 * in-memory 实现用于开发，redis 实现用于生产。
 * 通过 agent.memory.store 属性切换。
 */
public interface SessionRepository {
    ConversationContext loadOrCreate(String sessionId);
    void save(ConversationContext context);
    List<MemoryStore.SessionSummary> listSessions();
}
