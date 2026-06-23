package com.cyc.cyctest.agent.evaluation;

import com.cyc.cyctest.agent.core.AgentModels.ChatResponse;
import com.cyc.cyctest.agent.core.AgentModels.Evidence;
import com.cyc.cyctest.agent.core.IAgentRuntime;
import com.cyc.cyctest.agent.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * 批量评估执行器。
 * <p>
 * 工作流程：
 * 1. 从 cases.yaml 加载黄金集
 * 2. 对每条用例调用 AgentRuntime，收集 ChatResponse
 * 3. 规则评估（路由/工具覆盖/事实覆盖）—— 无需 LLM，毫秒级
 * 4. LLM-as-Judge（忠实度/相关性）—— LLM 不可用时跳过，不阻塞整体流程
 * 5. 汇总 EvalReport
 * <p>
 * presetSlots 处理方式：先发一条包含所有槽位信息的"预热消息"，
 * 让 SlotExtractionService 自然提取，再发真正的问题。
 * 这样无需侵入 MemoryStore 内部，保持评估路径与生产路径一致。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final IAgentRuntime agentRuntime;
    private final EvaluationService evaluationService;
    private final EvalDatasetLoader datasetLoader;
    private final LlmClient llmClient;

    public EvalRunner(IAgentRuntime agentRuntime,
                      EvaluationService evaluationService,
                      EvalDatasetLoader datasetLoader,
                      LlmClient llmClient) {
        this.agentRuntime = agentRuntime;
        this.evaluationService = evaluationService;
        this.datasetLoader = datasetLoader;
        this.llmClient = llmClient;
    }

    public EvalReport run() {
        List<EvalCase> cases = datasetLoader.load();
        if (cases.isEmpty()) {
            log.warn("EvalRunner: 黄金集为空，请检查 eval/cases.yaml");
            return emptyReport();
        }
        log.info("EvalRunner: 开始评估，共 {} 个用例，LLM-as-Judge: {}",
                cases.size(), llmClient.available() ? "启用" : "跳过（LLM 不可用）");

        List<EvalCaseResult> results = cases.stream().map(this::runCase).toList();
        EvalReport report = buildReport(results);

        log.info("EvalRunner: 评估完成 通过={}/{} 路由准确率={}% 平均忠实度={}",
                report.passedCases(), report.totalCases(),
                String.format("%.1f", report.routeAccuracy() * 100),
                String.format("%.2f", report.avgFaithfulness()));
        return report;
    }

    // ---- 单条用例执行 ----

    private EvalCaseResult runCase(EvalCase c) {
        log.info("EvalRunner: [{}] {}", c.caseId(), c.userText());
        // 每条用例使用独立 sessionId，隔离上下文
        String sessionId = "eval-" + c.caseId() + "-" + Instant.now().toEpochMilli();
        try {
            // 预设槽位：先发一条自然语言描述，让 SlotExtractionService 提取
            if (!c.presetSlots().isEmpty()) {
                agentRuntime.run(sessionId, buildSlotMessage(c.presetSlots()));
            }

            ChatResponse response = agentRuntime.run(sessionId, c.userText());

            // Layer 1：规则评估（无 LLM，快速）
            boolean routeCorrect = evalRoute(c, response);
            List<String> actualTools = extractActualToolCodes(response);
            double toolRecall = evalToolRecall(c, actualTools);
            double evidenceScore = avgEvidenceScore(response);
            double factCoverage = evalFactCoverage(c, response);

            // Layer 2：LLM-as-Judge（可选，LLM 不可用时跳过）
            double faithfulness = 0, relevance = 0, completeness = 0;
            if (llmClient.available() && response.answer() != null) {
                String context = buildEvidenceContext(response);
                EvaluationService.EvalResult judge =
                        evaluationService.evaluate(c.userText(), response.answer(), context);
                faithfulness = judge.faithfulness() / 10.0;
                relevance = judge.relevance() / 10.0;
                completeness = judge.completeness() / 10.0;
            }

            // passed：规则必须全过；LLM-as-Judge 有分时还要 faithfulness >= 0.7
            boolean passed = routeCorrect
                    && toolRecall >= 0.8
                    && factCoverage >= 0.8
                    && (!llmClient.available() || faithfulness >= 0.7);

            return new EvalCaseResult(
                    c.caseId(), c.userText(), c.tags(),
                    routeCorrect, toolRecall, evidenceScore, factCoverage,
                    faithfulness, relevance, completeness,
                    passed, passed ? null : buildFailReason(routeCorrect, toolRecall, factCoverage, faithfulness),
                    response.route() != null ? response.route().domainCode() : null,
                    actualTools);

        } catch (Exception e) {
            log.error("EvalRunner: 用例 [{}] 执行异常: {}", c.caseId(), e.getMessage());
            return EvalCaseResult.failed(c.caseId(), c.userText(), c.tags(), "执行异常: " + e.getMessage());
        }
    }

    // ---- 评估计算 ----

    private boolean evalRoute(EvalCase c, ChatResponse response) {
        if (c.expected().domainCode() == null) return true;
        return response.route() != null
                && c.expected().domainCode().equals(response.route().domainCode());
    }

    /**
     * 从 Evidence 列表中提取实际调用的 toolCode。
     * evidenceId 格式为 "tool_{toolCode}"（见 TaskExecutionEngine:108）。
     */
    private List<String> extractActualToolCodes(ChatResponse response) {
        if (response.evidence() == null) return List.of();
        return response.evidence().stream()
                .filter(e -> "tool".equals(e.type()))
                .map(e -> {
                    String id = e.evidenceId();
                    return id.startsWith("tool_") ? id.substring(5) : id;
                })
                .distinct()
                .toList();
    }

    private double evalToolRecall(EvalCase c, List<String> actualTools) {
        List<String> expected = c.expected().toolCodes();
        if (expected.isEmpty()) return 1.0;
        Set<String> actual = new HashSet<>(actualTools);
        long covered = expected.stream().filter(actual::contains).count();
        return (double) covered / expected.size();
    }

    private double avgEvidenceScore(ChatResponse response) {
        if (response.evidence() == null || response.evidence().isEmpty()) return 0;
        return response.evidence().stream()
                .filter(e -> "tool".equals(e.type()) || "knowledge".equals(e.type()))
                .mapToDouble(Evidence::score)
                .average().orElse(0);
    }

    private double evalFactCoverage(EvalCase c, ChatResponse response) {
        List<String> facts = c.expected().mustContainFacts();
        if (facts.isEmpty()) return 1.0;
        String answer = response.answer() != null ? response.answer() : "";
        long covered = facts.stream().filter(answer::contains).count();
        return (double) covered / facts.size();
    }

    private String buildEvidenceContext(ChatResponse response) {
        if (response.evidence() == null) return "";
        return response.evidence().stream()
                .map(e -> "[" + e.evidenceId() + "] " + e.title() + ": " + e.content())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 把 presetSlots Map 转成自然语言，让 SlotExtractionService 提取。
     * 例：{payOrderId=PAY001, env=PROD} → "支付单号 PAY001，生产环境。"
     */
    private String buildSlotMessage(Map<String, String> slots) {
        List<String> parts = new ArrayList<>();
        if (slots.containsKey("payOrderId")) parts.add("支付单号 " + slots.get("payOrderId"));
        if (slots.containsKey("orderId")) parts.add("订单号 " + slots.get("orderId"));
        if (slots.containsKey("checkoutId")) parts.add("checkoutId " + slots.get("checkoutId"));
        if (slots.containsKey("errorCode")) parts.add("错误码 " + slots.get("errorCode"));
        if (slots.containsKey("activityId")) parts.add("活动 ID " + slots.get("activityId"));
        if (slots.containsKey("couponId")) parts.add("券 ID " + slots.get("couponId"));
        if (slots.containsKey("env")) {
            parts.add(switch (slots.get("env").toUpperCase()) {
                case "PROD" -> "生产环境";
                case "PRE" -> "预发环境";
                case "DAILY" -> "日常环境";
                default -> slots.get("env") + " 环境";
            });
        }
        return String.join("，", parts) + "。";
    }

    private String buildFailReason(boolean routeCorrect, double toolRecall,
                                   double factCoverage, double faithfulness) {
        List<String> reasons = new ArrayList<>();
        if (!routeCorrect) reasons.add("路由错误");
        if (toolRecall < 0.8) reasons.add(String.format("工具覆盖不足(%.0f%%)", toolRecall * 100));
        if (factCoverage < 0.8) reasons.add(String.format("关键事实遗漏(%.0f%%)", factCoverage * 100));
        if (llmClient.available() && faithfulness < 0.7)
            reasons.add(String.format("忠实度不足(%.2f)", faithfulness));
        return String.join("；", reasons);
    }

    // ---- 报告聚合 ----

    private EvalReport buildReport(List<EvalCaseResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(EvalCaseResult::passed).count();

        return new EvalReport(
                Instant.now(), total, passed,
                total == 0 ? 0 : (double) passed / total,
                avg(results, r -> r.routeCorrect() ? 1.0 : 0.0),
                avg(results, EvalCaseResult::toolRecallRate),
                avg(results, EvalCaseResult::avgEvidenceScore),
                avg(results, EvalCaseResult::factCoverageRate),
                avg(results, EvalCaseResult::faithfulness),
                avg(results, EvalCaseResult::relevance),
                avg(results, EvalCaseResult::completeness),
                buildTagSummary(results),
                results.stream().filter(r -> !r.passed()).toList(),
                results
        );
    }

    private Map<String, EvalReport.TagSummary> buildTagSummary(List<EvalCaseResult> results) {
        Map<String, List<EvalCaseResult>> grouped = new LinkedHashMap<>();
        for (EvalCaseResult r : results) {
            for (String tag : r.tags()) {
                grouped.computeIfAbsent(tag, k -> new ArrayList<>()).add(r);
            }
        }
        Map<String, EvalReport.TagSummary> summary = new LinkedHashMap<>();
        grouped.forEach((tag, list) -> summary.put(tag, new EvalReport.TagSummary(
                list.size(),
                (int) list.stream().filter(EvalCaseResult::passed).count(),
                avg(list, r -> r.routeCorrect() ? 1.0 : 0.0),
                avg(list, EvalCaseResult::faithfulness)
        )));
        return summary;
    }

    private double avg(List<EvalCaseResult> list, ToDoubleFunction<EvalCaseResult> fn) {
        if (list.isEmpty()) return 0;
        return list.stream().mapToDouble(fn).average().orElse(0);
    }

    private EvalReport emptyReport() {
        return new EvalReport(Instant.now(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), List.of(), List.of());
    }
}
