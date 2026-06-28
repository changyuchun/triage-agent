package com.cyc.cyctest.agent.api;

import com.cyc.cyctest.agent.core.AgentModels.AgentProgressEvent;
import com.cyc.cyctest.agent.core.AgentModels.ChatRequest;
import com.cyc.cyctest.agent.core.AgentModels.ChatResponse;
import com.cyc.cyctest.agent.core.IAgentRuntime;
import reactor.core.Disposable;
import com.cyc.cyctest.agent.graph.GraphAgentRuntime;
import com.cyc.cyctest.agent.guardrails.GuardrailsService;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.memory.MemoryGraph;
import com.cyc.cyctest.agent.memory.MemoryStore;
import com.cyc.cyctest.agent.memory.MemoryStore.SessionSummary;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final IAgentRuntime agentRuntime;
    private final GraphAgentRuntime graphAgentRuntime;
    private final ToolRegistry toolRegistry;
    private final MemoryStore memoryStore;
    private final LlmClient llmClient;
    private final GuardrailsService guardrailsService;

    public AgentController(IAgentRuntime agentRuntime,
                           GraphAgentRuntime graphAgentRuntime,
                           ToolRegistry toolRegistry,
                           MemoryStore memoryStore,
                           LlmClient llmClient,
                           GuardrailsService guardrailsService) {
        this.agentRuntime = agentRuntime;
        this.graphAgentRuntime = graphAgentRuntime;
        this.toolRegistry = toolRegistry;
        this.memoryStore = memoryStore;
        this.llmClient = llmClient;
        this.guardrailsService = guardrailsService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        var guard = guardrailsService.check(request.sessionId(), request.message());
        if (guard.blocked()) throw new IllegalArgumentException(guard.reason());
        return agentRuntime.run(request.sessionId(), guard.sanitizedInput());
    }

    @GetMapping("/chat")
    public ChatResponse chatByGet(@RequestParam(defaultValue = "default") String sessionId,
                                  @RequestParam String message) {
        var guard = guardrailsService.check(sessionId, message);
        if (guard.blocked()) throw new IllegalArgumentException(guard.reason());
        return agentRuntime.run(sessionId, guard.sanitizedInput());
    }

    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return toolRegistry.definitions();
    }

    @GetMapping("/sessions")
    public List<SessionSummary> sessions() {
        return memoryStore.listSessions();
    }

    @GetMapping("/memory")
    public MemoryGraph memory(@RequestParam(defaultValue = "default") String sessionId) {
        return memoryStore.graph(sessionId);
    }

    @GetMapping("/memory/layers")
    public LayeredMemorySnapshot memoryLayers(@RequestParam(defaultValue = "default") String sessionId) {
        return memoryStore.layeredSnapshot(sessionId);
    }

    /**
     * 导出 Agent StateGraph 的 Mermaid 拓扑图（用于可视化和面试演示）。
     * 访问：GET /api/agent/graph
     */
    @GetMapping("/graph")
    public String graphTopology() {
        return graphAgentRuntime.getMermaidGraph();
    }

    /**
     * Agent 状态流 SSE：推送完整 Agent 执行链路的状态变更事件。
     * 适合前端展示 Thought→Action→Observation 的 ReAct 过程。
     */
    /**
     * Agent 进度流 SSE（ProgressCallback 方案）。
     * <p>
     * 与旧版的区别：旧版等 pipeline 跑完再一次性回放 trace，用户体验是假流；
     * 新版在每个阶段开始/完成时立即推送事件，用户能看到实时进度。
     * <p>
     * 实现模式：CompletableFuture + ProgressCallback。
     * - 简单：无 Reactor 依赖，callback = lambda，推送逻辑清晰可读。
     * - 适合面试演示：能直观看到 EXTRACT→ROUTE→PLAN→EXECUTE→SYNTHESIZE 的事件序列。
     * - 与 /chat/stream/v2 的区别：无 token 级流式，答案一次性推送；
     *   但各阶段进度是真实时，首字节延迟 < 200ms。
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(defaultValue = "default") String sessionId,
                                 @RequestParam String message) {
        var guard = guardrailsService.check(sessionId, message);
        SseEmitter emitter = new SseEmitter(60_000L);
        if (guard.blocked()) {
            CompletableFuture.runAsync(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data(guard.reason()));
                    emitter.complete();
                } catch (Exception e) { emitter.completeWithError(e); }
            });
            return emitter;
        }
        String safeInput = guard.sanitizedInput();
        CompletableFuture.runAsync(() -> {
            try {
                emitter.send(SseEmitter.event().name("run_start").data("sessionId=" + sessionId));

                // ProgressCallback：阶段进度 + token 级打字机回调
                ChatResponse response = agentRuntime.runWithCallback(sessionId, safeInput,
                        new com.cyc.cyctest.agent.core.AgentModels.ProgressCallback() {
                            @Override
                            public void onProgress(String type, String msg) {
                                try { emitter.send(SseEmitter.event().name(type).data(msg)); }
                                catch (Exception ignored) {}
                            }
                            @Override
                            public void onToken(String delta) {
                                try { emitter.send(SseEmitter.event().name("token").data(delta)); }
                                catch (Exception ignored) {}
                            }
                        });

                if (response.waitingUserInput()) {
                    emitter.send(SseEmitter.event().name("clarify_required").data(response.clarifyQuestion()));
                } else {
                    emitter.send(SseEmitter.event().name("answer").data(response.answer()));
                }
                emitter.send(SseEmitter.event().name("memory_snapshot").data(memoryStore.layeredSnapshot(sessionId)));
                emitter.send(SseEmitter.event().name("done").data(response.state()));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * 真流式 SSE（v2）：实时推送 Agent 各阶段进度 + LLM token 级输出，首字延迟极低。
     * <p>
     * 事件类型：
     * - extract / route / plan / execute / reretrieve：阶段开始通知
     * - synthesizing：开始生成答案
     * - token：LLM 逐 token 字符块（可直接渲染打字机效果）
     * - done：包含完整 ChatResponse 的结束事件
     * - error：异常事件
     * <p>
     * 与 /chat/stream 的区别：
     * - /chat/stream：完整 pipeline 跑完后一次性回放 trace，非真实时
     * - /chat/stream/v2：各阶段完成时立即推送，答案 token 级实时输出
     */
    @GetMapping(value = "/chat/stream/v2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamV2(
            @RequestParam(defaultValue = "default") String sessionId,
            @RequestParam String message) {
        var guard = guardrailsService.check(sessionId, message);
        SseEmitter emitter = new SseEmitter(120_000L);
        if (guard.blocked()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(guard.reason()));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }
        Disposable subscription = agentRuntime.stream(sessionId, guard.sanitizedInput())
                .subscribe(
                        event -> {
                            try {
                                if (event instanceof AgentProgressEvent.Progress p) {
                                    emitter.send(SseEmitter.event().name(p.type()).data(p.message()));
                                } else if (event instanceof AgentProgressEvent.Token t) {
                                    emitter.send(SseEmitter.event().name("token").data(t.delta()));
                                } else if (event instanceof AgentProgressEvent.Done d) {
                                    emitter.send(SseEmitter.event().name("done").data(d.response()));
                                }
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );
        // 客户端断连时取消订阅，避免后台 pipeline 继续空跑
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        return emitter;
    }

    /**
     * Token 级流式 SSE：直接对 LLM 进行 token-by-token 推送，首字延迟极低。
     * <p>
     * 与 /chat/stream 的区别：
     * - /chat/stream：完整 Agent 循环（路由→计划→执行→合成），推送状态事件
     * - /llm/stream：直接调用 LLM，逐 token 推送，绕过 Agent 循环
     * <p>
     * 底层：chatModel.stream() → Flux<String> → SseEmitter.send(token)
     * 每个 SSE event 名为 "token"，最后发送 "done" 事件标志结束。
     */
    @GetMapping(value = "/llm/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter llmStream(
            @RequestParam(defaultValue = "你是一个专业的 AI 助手，请用中文回答") String system,
            @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(120_000L);

        // Flux 在 reactor 调度线程执行，subscribe 后立即返回 emitter
        llmClient.streamTokens(system, message)
                .subscribe(
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
        return emitter;
    }
}
