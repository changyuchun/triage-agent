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
import java.util.stream.Collectors;

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
     * 执行计划步骤。
     * <p>
     * Parallel Tool Calling：所有 TOOL_CALL 步骤并行执行（CompletableFuture.allOf），
     * 而 KNOWLEDGE_RETRIEVE 和 DOMAIN_ANALYSIS 保持串行顺序。
     * <p>
     * 类比：LLM 的 Parallel Tool Calling——模型一次输出多个 ToolCall JSON，
     * 框架（这里是 Spring）并行执行，减少总等待时间。
     * 对比 OpenAI API：tool_choice=auto 时，LLM 可能返回多个 tool_calls，需要并行执行。
     */
    public EvidencePackage execute(ExecutionPlan plan, SlotState slots, RouteResult route) {
        List<Evidence> evidence = new ArrayList<>();

        // 1. 先执行所有 KNOWLEDGE_RETRIEVE（顺序依赖，需要先有知识才能分析）
        for (PlanStep step : plan.steps()) {
            if (step.type() == StepType.KNOWLEDGE_RETRIEVE) {
                evidence.addAll(executeKnowledge(step, route));
            }
        }

        // 2. Parallel Tool Calling：所有 TOOL_CALL 并行执行
        List<PlanStep> toolSteps = plan.steps().stream()
                .filter(s -> s.type() == StepType.TOOL_CALL)
                .toList();
        if (!toolSteps.isEmpty()) {
            List<CompletableFuture<Evidence>> futures = toolSteps.stream()
                    .map(step -> CompletableFuture.supplyAsync(() -> executeTool(step, slots)))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            futures.stream()
                    .map(f -> f.getNow(null))
                    .filter(e -> e != null)
                    .forEach(evidence::add);
        }

        // 3. DOMAIN_ANALYSIS 在工具和知识都收集完后执行
        for (PlanStep step : plan.steps()) {
            if (step.type() == StepType.DOMAIN_ANALYSIS) {
                evidence.add(executeDomainAnalysis(step, evidence, route));
            }
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

    private Evidence executeDomainAnalysis(PlanStep step, List<Evidence> existingEvidence, RouteResult route) {
        long toolCount = existingEvidence.stream().filter(e -> "tool".equals(e.type())).count();
        long knowledgeCount = existingEvidence.stream().filter(e -> "knowledge".equals(e.type())).count();
        String content = "领域分析基于 " + toolCount + " 条工具证据和 " + knowledgeCount + " 条知识证据。"
                + "当前领域为 " + route.domainName() + "/" + route.subDomainName()
                + "，分析结论必须以已有 evidenceId 为依据，不能生成未查询事实。";
        return new Evidence("analysis_" + step.stepId(), "analysis", "领域分析", content, 0.65,
                Map.of("domainCode", route.domainCode(), "subDomainCode", route.subDomainCode()));
    }

    private double quality(List<Evidence> evidence) {
        if (evidence.isEmpty()) {
            return 0;
        }
        boolean hasTool = evidence.stream().anyMatch(e -> "tool".equals(e.type()));
        boolean hasKnowledge = evidence.stream().anyMatch(e -> "knowledge".equals(e.type()));
        double maxScore = evidence.stream().mapToDouble(Evidence::score).max().orElse(0);
        double score = Math.min(1.0, maxScore);
        if (hasTool) {
            score += 0.35;
        }
        if (hasKnowledge) {
            score += 0.20;
        }
        return Math.min(1.0, score);
    }
}
