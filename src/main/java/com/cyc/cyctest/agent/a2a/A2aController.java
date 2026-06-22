package com.cyc.cyctest.agent.a2a;

import com.cyc.cyctest.agent.core.AgentRuntime;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A（Agent-to-Agent）协议控制器。
 * <p>
 * Google 提出的 Agent 互通标准，解决 Agent↔Agent 协作问题（MCP 解决 Agent↔Tool）。
 * <p>
 * 核心端点：
 * - GET /.well-known/agent.json → Agent Card（Agent 的"名片"）
 * - POST /api/a2a/tasks → 创建异步任务
 * - GET  /api/a2a/tasks/{taskId} → 查询任务状态（状态机）
 * - GET  /api/a2a/tasks/{taskId}/stream → SSE 实时追踪任务进展
 * <p>
 * Task 状态机：submitted → working → (input-required →) completed | failed
 * 类比微服务中的订单状态流转：提交 → 处理中 → 完成/失败。
 */
@RestController
public class A2aController {

    private static final Logger log = LoggerFactory.getLogger(A2aController.class);

    private final AgentRuntime agentRuntime;
    // 任务存储（生产应用替换为 Redis 或 DB）
    private final Map<String, A2aTask> tasks = new ConcurrentHashMap<>();

    public A2aController(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    // ==================== Agent Card ====================

    /**
     * A2A Agent Card：发布当前 Agent 的能力描述，供其他 Agent 发现和调用。
     * 类比 Nacos/Eureka 的服务元数据，路径约定为 /.well-known/agent.json。
     */
    @GetMapping("/.well-known/agent.json")
    public AgentCard agentCard() {
        return new AgentCard(
                "cyctest-agent",
                "Java AI Agent - 面试演示项目，覆盖 Agent 开发全知识图谱",
                "1.0.0",
                "http://localhost:8080",
                new AgentCapabilities(true, false, true),
                List.of(
                        new AgentSkill("multi-turn-chat", "多轮对话",
                                "支持槽位提取、多轮上下文、L1-L5 分层记忆、Redis 持久化"),
                        new AgentSkill("hybrid-rag", "混合检索增强生成",
                                "BM25 + 向量检索 + RRF 融合，支持 PDF/TXT/MD 文档摄入"),
                        new AgentSkill("tool-calling", "领域工具调用",
                                "支付/交易/营销领域工具，@Tool 注解自动注册，同时对外暴露 MCP Server"),
                        new AgentSkill("episodic-recall", "情景记忆召回",
                                "历史对话 Embedding 存储，跨会话语义召回相关片段")
                ),
                List.of(new AuthMethod("none", "无需认证"))
        );
    }

    // ==================== A2A Task 状态机 ====================

    @PostMapping("/api/a2a/tasks")
    public A2aTask createTask(@RequestBody A2aTaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        String sessionId = "a2a-" + taskId.substring(0, 8);
        A2aTask task = new A2aTask(taskId, "submitted", request.message(), sessionId,
                Instant.now(), null, null, null);
        tasks.put(taskId, task);

        // 异步执行 Agent 任务
        CompletableFuture.runAsync(() -> {
            try {
                tasks.put(taskId, task.withStatus("working"));
                var response = agentRuntime.run(sessionId, request.message());
                String status = response.waitingUserInput() ? "input-required" : "completed";
                tasks.put(taskId, task.withStatus(status)
                        .withResult(response.answer())
                        .withCompletedAt(Instant.now()));
                log.info("[A2A] Task completed taskId={} status={}", taskId, status);
            } catch (Exception e) {
                log.error("[A2A] Task failed taskId={}", taskId, e);
                tasks.put(taskId, task.withStatus("failed")
                        .withResult("任务执行失败: " + e.getMessage())
                        .withCompletedAt(Instant.now()));
            }
        });

        return task;
    }

    @GetMapping("/api/a2a/tasks/{taskId}")
    public A2aTask getTask(@PathVariable String taskId) {
        A2aTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskId);
        return task;
    }

    /** SSE 实时追踪任务进展（Push Notification 的简化实现） */
    @GetMapping(value = "/api/a2a/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@PathVariable String taskId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                int maxPolls = 120;
                while (maxPolls-- > 0) {
                    A2aTask task = tasks.get(taskId);
                    if (task == null) {
                        emitter.send(SseEmitter.event().name("error").data("task_not_found"));
                        emitter.complete();
                        return;
                    }
                    emitter.send(SseEmitter.event().name("status").data(task));
                    if ("completed".equals(task.status()) || "failed".equals(task.status())) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    // ==================== Records ====================

    public record AgentCard(
            String name,
            String description,
            String version,
            String url,
            AgentCapabilities capabilities,
            List<AgentSkill> skills,
            List<AuthMethod> authentication
    ) {}

    public record AgentCapabilities(
            boolean streaming,
            @JsonProperty("pushNotifications") boolean pushNotifications,
            @JsonProperty("a2a") boolean a2a
    ) {}

    public record AgentSkill(String id, String name, String description) {}

    public record AuthMethod(String type, String description) {}

    public record A2aTaskRequest(String message, String sessionId) {}

    public record A2aTask(
            String taskId,
            String status,
            String input,
            String sessionId,
            Instant createdAt,
            Instant completedAt,
            String result,
            String error
    ) {
        A2aTask withStatus(String s) {
            return new A2aTask(taskId, s, input, sessionId, createdAt, completedAt, result, error);
        }

        A2aTask withResult(String r) {
            return new A2aTask(taskId, status, input, sessionId, createdAt, completedAt, r, error);
        }

        A2aTask withCompletedAt(Instant t) {
            return new A2aTask(taskId, status, input, sessionId, createdAt, t, result, error);
        }
    }
}
