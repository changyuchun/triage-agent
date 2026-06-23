package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.Evidence;
import com.cyc.cyctest.agent.core.AgentModels.EvidencePackage;
import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.PlanStep;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.core.AgentModels.StepType;
import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import com.cyc.cyctest.agent.rag.KnowledgeModels.RetrieveRequest;
import com.cyc.cyctest.agent.rag.KnowledgeRetriever;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import com.cyc.cyctest.agent.tool.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class TaskExecutionEngine {
    private final KnowledgeRetriever knowledgeRetriever;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public TaskExecutionEngine(KnowledgeRetriever knowledgeRetriever, ToolRegistry toolRegistry, AgentProperties properties) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 执行计划步骤，返回 EvidencePackage。
     * <p>
     * 执行顺序：
     * 1. KNOWLEDGE_RETRIEVE 顺序执行（知识召回）
     * 2. TOOL_CALL 全部并行执行（Parallel Tool Calling，减少总等待时间）
     * <p>
     * 类比 OpenAI Parallel Tool Calling：LLM 一次返回多个 tool_calls，
     * 框架并行执行后把所有结果一起返回给模型——本项目同理。
     */
    public EvidencePackage execute(ExecutionPlan plan, SlotState slots, RouteResult route) {
        List<Evidence> evidence = new ArrayList<>();

        // 1. 知识检索（顺序）
        for (PlanStep step : plan.steps()) {
            if (step.type() == StepType.KNOWLEDGE_RETRIEVE) {
                evidence.addAll(executeKnowledge(step, route));
            }
        }

        // 2. Parallel Tool Calling（并行）
        List<PlanStep> toolSteps = plan.steps().stream()
                .filter(s -> s.type() == StepType.TOOL_CALL)
                .toList();
        if (!toolSteps.isEmpty()) {
            List<CompletableFuture<Evidence>> futures = toolSteps.stream()
                    .map(step -> CompletableFuture.supplyAsync(() -> executeTool(step, slots)))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            futures.stream().map(f -> f.getNow(null)).filter(e -> e != null).forEach(evidence::add);
        }

        double quality = quality(evidence);
        return new EvidencePackage(List.copyOf(evidence), quality);
    }

    private List<Evidence> executeKnowledge(PlanStep step, RouteResult route) {
        RetrieveRequest request = new RetrieveRequest(
                step.query(),
                route.domainCode(),
                route.subDomainCode(),
                Map.of(),
                properties.runtime().finalTopK()
        );
        List<KnowledgeChunk> chunks = knowledgeRetriever.retrieve(request);
        List<Evidence> evidence = new ArrayList<>();
        int index = 0;
        for (KnowledgeChunk chunk : chunks) {
            evidence.add(new Evidence(
                    "knowledge_" + (++index) + "_" + chunk.chunkId(),
                    "knowledge",
                    chunk.title(),
                    chunk.content(),
                    chunk.score(),
                    Map.of("chunkId", chunk.chunkId(), "docId", chunk.docId(), "source", chunk.metadata().getOrDefault("source", "kb"))
            ));
        }
        return evidence;
    }

    private Evidence executeTool(PlanStep step, SlotState slots) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("env", slots.env().name());
        args.put("objectId", slots.primaryObjectId());
        args.put("payOrderId", slots.payOrderId());
        args.put("orderId", slots.orderId());
        args.put("checkoutId", slots.checkoutId());
        args.put("activityId", slots.activityId());
        args.put("couponId", slots.couponId());

        ToolExecutionResult result = toolRegistry.call(step.toolCode(), args);
        if (result.success()) {
            return new Evidence("tool_" + step.toolCode(), "tool", result.title(), result.summary(), 1.0, result.data());
        }
        return new Evidence("tool_" + step.toolCode(), "tool_error", result.title(), result.errorMessage(), 0.1,
                Map.of("errorCode", result.errorCode()));
    }

    /**
     * 证据质量评分：仅基于真实证据（knowledge/tool），不受伪 Evidence 污染。
     * <p>
     * 评分规则：max(knowledge score) 为基础，有工具证据 +0.35，有知识证据 +0.20，上限 1.0。
     * tool_error 类型不计入 hasTool，避免工具失败时虚报质量。
     */
    private double quality(List<Evidence> evidence) {
        if (evidence.isEmpty()) return 0;
        boolean hasTool = evidence.stream().anyMatch(e -> "tool".equals(e.type()));
        boolean hasKnowledge = evidence.stream().anyMatch(e -> "knowledge".equals(e.type()));
        double maxScore = evidence.stream()
                .filter(e -> "tool".equals(e.type()) || "knowledge".equals(e.type()))
                .mapToDouble(Evidence::score).max().orElse(0);
        double score = maxScore;
        if (hasTool) score += 0.35;
        if (hasKnowledge) score += 0.20;
        return Math.min(1.0, score);
    }
}
