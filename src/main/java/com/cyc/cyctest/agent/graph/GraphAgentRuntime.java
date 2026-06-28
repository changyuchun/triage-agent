package com.cyc.cyctest.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.cyc.cyctest.agent.cache.SemanticCacheService;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.IAgentRuntime;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.MemoryStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 运行时（Spring AI Alibaba StateGraph 实现）。
 * <p>
 * 与经典实现（{@link com.cyc.cyctest.agent.core.AgentRuntime}）的对比：
 * <pre>
 * 经典实现（while-switch）：
 *   while(state != DONE) { state = switch(state) { ... } }
 *   - 控制流在代码中，状态隐式
 *   - 无法可视化、无 Checkpoint、难以扩展
 *
 * StateGraph 实现（本类）：
 *   compiledGraph.invoke(inputs, config) → 图引擎驱动节点执行
 *   - 控制流在拓扑定义中，状态显式（OverAllState Map）
 *   - MemorySaver Checkpoint（按 threadId=sessionId 存储）
 *   - graphResponseStream() 支持节点级流式推送
 *   - getGraph(MERMAID) 直接导出可视化拓扑图
 * </pre>
 * <p>
 * {@code @Primary}：当 Spring 容器有多个 {@link IAgentRuntime} 实现时，优先注入本类。
 */
@Primary
@Service
public class GraphAgentRuntime implements IAgentRuntime {

    private final CompiledGraph compiledGraph;
    private final MemoryStore memoryStore;
    private final SemanticCacheService semanticCacheService;

    public GraphAgentRuntime(@Qualifier("agentCompiledGraph") CompiledGraph compiledGraph,
                             MemoryStore memoryStore,
                             SemanticCacheService semanticCacheService) {
        this.compiledGraph = compiledGraph;
        this.memoryStore = memoryStore;
        this.semanticCacheService = semanticCacheService;
    }

    @Override
    public ChatResponse run(String sessionId, String userText) {
        // ---- L0：语义缓存（跳过完整 Graph 执行）----
        Optional<String> cached = semanticCacheService.get(userText);
        if (cached.isPresent()) {
            ConversationContext mem = memoryStore.load(sessionId);
            mem.addTurn("user", userText);
            mem.addTurn("assistant", "[CACHE] " + cached.get());
            memoryStore.save(mem);
            return new ChatResponse(sessionId, AgentState.DONE.name(), false, null, null,
                    cached.get(), null, null, List.of(), List.of("semantic_cache_hit"));
        }

        // ---- 初始化：记录用户输入，加载历史槽位 ----
        ConversationContext memory = memoryStore.load(sessionId);
        memory.addTurn("user", userText);
        memoryStore.save(memory);

        // ---- 构建 Graph 初始状态 ----
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(AgentStateKeys.SESSION_ID,  sessionId);
        inputs.put(AgentStateKeys.USER_TEXT,   userText);
        inputs.put(AgentStateKeys.RETRY_COUNT, 0);
        inputs.put(AgentStateKeys.WAITING,     false);

        // threadId = sessionId：Graph Checkpoint 按会话隔离，支持多轮对话中断恢复
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        // ---- 驱动 StateGraph 同步执行 ----
        Optional<OverAllState> result = compiledGraph.invoke(inputs, config);
        if (result.isEmpty()) {
            return new ChatResponse(sessionId, AgentState.FAILED.name(), false, null, null,
                    "Agent 图执行异常，未返回有效状态", null, null, List.of(),
                    List.of("graph_invoke_empty"));
        }

        // ---- 从最终 OverAllState 提取结果 ----
        OverAllState finalState = result.get();
        String answer   = finalState.value(AgentStateKeys.ANSWER, "");
        boolean waiting = finalState.value(AgentStateKeys.WAITING, false);
        String clarifyQ = (String) finalState.value(AgentStateKeys.CLARIFY_QUESTION).orElse(null);
        SlotState slots = finalState.value(AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route        = getTyped(finalState, AgentStateKeys.ROUTE);
        EvidencePackage evidence = finalState.value(AgentStateKeys.EVIDENCE, EvidencePackage.empty());
        String stateStr = finalState.value(AgentStateKeys.AGENT_STATE, AgentState.DONE.name());
        @SuppressWarnings("unchecked")
        List<String> trace = (List<String>) finalState.value(AgentStateKeys.TRACE).orElse(List.of());

        // ---- L0 语义缓存写入 ----
        if (!waiting && answer != null && !answer.isBlank()) {
            semanticCacheService.put(userText, answer);
        }

        return new ChatResponse(
                sessionId, stateStr, waiting,
                waiting ? clarifyQ : null,
                waiting ? "请使用相同 sessionId 继续补充信息，下一轮会继承已提取槽位。" : null,
                answer, slots, route, evidence.evidence(), trace);
    }

    /**
     * Graph 版本流式实现：在 boundedElastic 线程上运行 compiledGraph.invoke()（同步阻塞），
     * 完成后将 trace 作为 Progress 事件回放，最后推送 Done。
     * 与状态机版本的区别：无法在各阶段中途推送事件（Graph 内部状态不透传给外部 Sink）。
     */
    @Override
    public Flux<AgentProgressEvent> stream(String sessionId, String userText) {
        return Flux.<AgentProgressEvent>create(sink -> {
            sink.next(new AgentProgressEvent.Progress("run_start", "Agent 正在处理，请稍候..."));
            ChatResponse response = run(sessionId, userText);
            // 将已完成的 trace 作为进度事件回放给前端
            for (String trace : response.trace()) {
                sink.next(new AgentProgressEvent.Progress("state_change", trace));
            }
            sink.next(new AgentProgressEvent.Done(response));
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 获取图 Mermaid 可视化拓扑（用于 /api/agent/graph 接口或面试演示）。
     */
    public String getMermaidGraph() {
        return compiledGraph.getGraph(com.alibaba.cloud.ai.graph.GraphRepresentation.Type.MERMAID).content();
    }

    @SuppressWarnings("unchecked")
    private <T> T getTyped(OverAllState state, String key) {
        return (T) state.value(key).orElse(null);
    }
}
