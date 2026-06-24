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
import com.cyc.cyctest.agent.skill.SkillRegistry;
import com.cyc.cyctest.agent.tool.AiTool;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 任务执行引擎——实现 LLMCompiler DAG 执行模式。
 * <p>
 * 执行策略：
 * 1. KNOWLEDGE_RETRIEVE 步骤顺序执行（知识召回）
 * 2. TOOL_CALL 步骤按 dependsOn 做拓扑排序，同层并行（Parallel Tool Calling），
 *    跨层顺序执行，并通过 StepResultContext 做 ${stepId.field} 变量替换。
 * <p>
 * 关键改进（相对于旧版无脑并行）：
 * - log_query 必须等 payment_query 完成后才执行，拿到真实 errorCode 而非 Slot 空值
 * - 条件步骤（condition 字段）在前置步骤结果不满足时跳过，避免无效工具调用
 */
@Service
public class TaskExecutionEngine {

    private final KnowledgeRetriever knowledgeRetriever;
    private final SkillRegistry skillRegistry;
    private final AgentProperties properties;

    public TaskExecutionEngine(KnowledgeRetriever knowledgeRetriever,
                               SkillRegistry skillRegistry,
                               AgentProperties properties) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.skillRegistry = skillRegistry;
        this.properties = properties;
    }

    public EvidencePackage execute(ExecutionPlan plan, SlotState slots, RouteResult route) {
        List<Evidence> evidence = new ArrayList<>();
        StepResultContext resultCtx = new StepResultContext(slots);

        // 1. 知识检索（顺序）
        for (PlanStep step : plan.steps()) {
            if (step.type() == StepType.KNOWLEDGE_RETRIEVE) {
                evidence.addAll(executeKnowledge(step, route));
            }
        }

        // 2. 工具调用：DAG 拓扑排序 + 层内并行
        List<PlanStep> toolSteps = plan.steps().stream()
                .filter(s -> s.type() == StepType.TOOL_CALL)
                .toList();

        List<List<PlanStep>> layers = topoSort(toolSteps);
        for (List<PlanStep> layer : layers) {
            // 过滤掉条件不满足的步骤
            List<PlanStep> runnable = layer.stream()
                    .filter(step -> resultCtx.evaluateCondition(step.condition()))
                    .toList();

            if (runnable.isEmpty()) continue;

            // 同层并行执行
            List<CompletableFuture<Map.Entry<String, ToolExecutionResult>>> futures =
                    runnable.stream()
                            .map(step -> CompletableFuture.supplyAsync(() -> {
                                // 变量替换：${payment_query_step.errorCode} → 实际值
                                Map<String, Object> resolvedArgs = resultCtx.resolveArgs(step.args());
                                // Slot 全量兜底（确保工具参数有默认值）
                                Map<String, Object> merged = slotDefaults(slots);
                                merged.putAll(resolvedArgs);
                                ToolExecutionResult result = callTool(step.toolCode(), merged);
                                return Map.entry(step.stepId(), result);
                            }))
                            .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<Map.Entry<String, ToolExecutionResult>> f : futures) {
                Map.Entry<String, ToolExecutionResult> entry = f.getNow(null);
                if (entry == null) continue;
                // 存入上下文供后续步骤引用
                resultCtx.store(entry.getKey(), entry.getValue());
                evidence.add(toEvidence(entry.getKey(), entry.getValue()));
            }
        }

        return new EvidencePackage(List.copyOf(evidence), quality(evidence));
    }

    /** 拓扑排序——Kahn 算法，返回按层分组的执行顺序，同层步骤可并行。 */
    private List<List<PlanStep>> topoSort(List<PlanStep> steps) {
        Map<String, PlanStep> byId = steps.stream()
                .collect(Collectors.toMap(PlanStep::stepId, s -> s));
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>(); // stepId → 依赖它的 stepId 列表

        for (PlanStep step : steps) {
            inDegree.put(step.stepId(), step.dependsOn().size());
            for (String dep : step.dependsOn()) {
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(step.stepId());
            }
        }

        List<List<PlanStep>> layers = new ArrayList<>();
        Queue<String> ready = new ArrayDeque<>();
        inDegree.forEach((id, deg) -> { if (deg == 0) ready.add(id); });

        while (!ready.isEmpty()) {
            List<PlanStep> layer = new ArrayList<>();
            int size = ready.size();
            for (int i = 0; i < size; i++) {
                String id = ready.poll();
                if (id != null && byId.containsKey(id)) layer.add(byId.get(id));
            }
            if (!layer.isEmpty()) layers.add(layer);
            for (PlanStep step : layer) {
                List<String> deps = dependents.getOrDefault(step.stepId(), List.of());
                for (String dep : deps) {
                    int remaining = inDegree.merge(dep, -1, Integer::sum);
                    if (remaining == 0) ready.add(dep);
                }
            }
        }
        return layers;
    }

    private ToolExecutionResult callTool(String toolCode, Map<String, Object> args) {
        Optional<AiTool> tool = skillRegistry.toolFor(toolCode);
        if (tool.isEmpty()) {
            return ToolExecutionResult.failed("TOOL_NOT_FOUND", "未注册工具: " + toolCode);
        }
        AiTool t = tool.get();
        // 必填参数校验
        for (String required : t.definition().requiredFields()) {
            Object v = args.get(required);
            if (v == null || v.toString().isBlank()) {
                return ToolExecutionResult.failed("TOOL_PARAM_MISSING", "缺少工具参数: " + required);
            }
        }
        try {
            return t.execute(new ToolExecutionRequest(toolCode, args));
        } catch (Exception e) {
            return ToolExecutionResult.failed("TOOL_EXCEPTION", e.getMessage());
        }
    }

    private List<Evidence> executeKnowledge(PlanStep step, RouteResult route) {
        RetrieveRequest request = new RetrieveRequest(
                step.query(), route.domainCode(), route.subDomainCode(),
                Map.of(), properties.runtime().finalTopK());
        List<KnowledgeChunk> chunks = knowledgeRetriever.retrieve(request);
        List<Evidence> evidence = new ArrayList<>();
        int index = 0;
        for (KnowledgeChunk chunk : chunks) {
            evidence.add(new Evidence(
                    "knowledge_" + (++index) + "_" + chunk.chunkId(),
                    "knowledge", chunk.title(), chunk.content(), chunk.score(),
                    Map.of("chunkId", chunk.chunkId(), "docId", chunk.docId(),
                            "source", chunk.metadata().getOrDefault("source", "kb"))));
        }
        return evidence;
    }

    private Evidence toEvidence(String stepId, ToolExecutionResult result) {
        if (result.success()) {
            return new Evidence("tool_" + stepId, "tool",
                    result.title(), result.summary(), 1.0, result.data());
        }
        return new Evidence("tool_" + stepId, "tool_error",
                result.title(), result.errorMessage(), 0.1,
                Map.of("errorCode", String.valueOf(result.errorCode())));
    }

    /** Slot 全量兜底——任何工具参数的默认来源，被 resolvedArgs 中的精确值覆盖。 */
    private Map<String, Object> slotDefaults(SlotState slots) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("env", slots.env() != null ? slots.env().name() : "PROD");
        defaults.put("objectId", slots.primaryObjectId());
        defaults.put("payOrderId", slots.payOrderId());
        defaults.put("orderId", slots.orderId());
        defaults.put("checkoutId", slots.checkoutId());
        defaults.put("activityId", slots.activityId());
        defaults.put("couponId", slots.couponId());
        defaults.put("errorCode", slots.errorCode());
        return defaults;
    }

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
