package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.memory.ConversationContext.MemoryPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储（开发/测试模式）。
 * 优点：零依赖、启动快。
 * 缺点：重启丢失、无法横向扩展。
 * 通过 agent.memory.store=in-memory 启用（默认）。
 */
@Component
@ConditionalOnProperty(name = "agent.memory.store", havingValue = "in-memory", matchIfMissing = true)
public class InMemorySessionRepository implements SessionRepository {

    private final Map<String, ConversationContext> sessions = new ConcurrentHashMap<>();
    private final MemoryPolicy memoryPolicy;

    public InMemorySessionRepository(AgentProperties properties) {
        this.memoryPolicy = MemoryPolicy.from(properties.memory());
    }

    @Override
    public ConversationContext loadOrCreate(String sessionId) {
        String key = normalize(sessionId);
        return sessions.computeIfAbsent(key, sid -> new ConversationContext(sid, memoryPolicy));
    }

    @Override
    public void save(ConversationContext context) {
        sessions.put(context.sessionId(), context);
    }

    @Override
    public List<MemoryStore.SessionSummary> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing(ConversationContext::createdAt).reversed())
                .map(s -> new MemoryStore.SessionSummary(
                        s.sessionId(), s.title(), s.createdAt(), s.clarifyCount(), s.pendingClarifyQuestion()))
                .toList();
    }

    private static String normalize(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }
}
