package com.cyc.cyctest.agent.memory;

import com.cyc.cyctest.agent.core.AgentModels.Evidence;
import com.cyc.cyctest.agent.core.AgentModels.EvidencePackage;
import com.cyc.cyctest.agent.core.AgentModels.ExecutionPlan;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.ConversationMemory;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.EvidenceMemory;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.SummaryMemory;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.SystemMemory;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.Turn;
import com.cyc.cyctest.agent.memory.LayeredMemorySnapshot.WorkingMemory;
import com.cyc.cyctest.agent.memory.MemoryGraph.MemoryEdge;
import com.cyc.cyctest.agent.memory.MemoryGraph.MemoryNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ConversationContext {
    private final String sessionId;
    private final Instant createdAt;
    private SlotState slotState = SlotState.empty();
    private RouteResult currentRoute;
    private ExecutionPlan currentPlan;
    private EvidencePackage evidencePackage = EvidencePackage.empty();
    private String currentGoal;
    private String summary = "暂无摘要";
    private Instant summaryUpdatedAt;
    private int compressedTurnCount;
    private int clarifyCount;
    private String pendingClarifyQuestion;
    private String title = "新会话";
    private String lastTurnNodeId;
    private final List<String> turns = new ArrayList<>();
    private final List<Turn> structuredTurns = new ArrayList<>();
    private final Map<String, MemoryNode> nodes = new LinkedHashMap<>();
    private final List<MemoryEdge> edges = new ArrayList<>();

    /** 正常创建新会话 */
    public ConversationContext(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        putNode("session:" + sessionId, "SESSION", "会话 " + sessionId, Map.of("sessionId", sessionId));
    }

    /** 从 Redis 快照恢复会话，不产生新节点 */
    ConversationContext(ConversationSnapshot s) {
        this.sessionId = s.sessionId();
        this.createdAt = s.createdAt() != null ? s.createdAt() : Instant.now();
        this.title = s.title() != null ? s.title() : "新会话";
        this.summary = s.summary() != null ? s.summary() : "暂无摘要";
        this.summaryUpdatedAt = s.summaryUpdatedAt();
        this.compressedTurnCount = s.compressedTurnCount();
        this.clarifyCount = s.clarifyCount();
        this.pendingClarifyQuestion = s.pendingClarifyQuestion();
        this.currentGoal = s.currentGoal();
        this.slotState = s.slotState() != null ? s.slotState() : SlotState.empty();
        this.currentRoute = s.currentRoute();
        this.currentPlan = s.currentPlan();
        this.evidencePackage = s.evidencePackage() != null ? s.evidencePackage() : EvidencePackage.empty();
        this.lastTurnNodeId = s.lastTurnNodeId();
        if (s.turns() != null) this.turns.addAll(s.turns());
        if (s.structuredTurns() != null) this.structuredTurns.addAll(s.structuredTurns());
        if (s.nodes() != null) s.nodes().forEach(n -> this.nodes.put(n.id(), n));
        if (s.edges() != null) this.edges.addAll(s.edges());
    }

    /** 导出为 Redis 可序列化快照 */
    public ConversationSnapshot toSnapshot() {
        return new ConversationSnapshot(
                sessionId, createdAt, title,
                List.copyOf(turns), List.copyOf(structuredTurns),
                compressedTurnCount, clarifyCount,
                pendingClarifyQuestion, currentGoal,
                summary, summaryUpdatedAt,
                slotState, currentRoute, currentPlan, evidencePackage,
                new ArrayList<>(nodes.values()), new ArrayList<>(edges),
                lastTurnNodeId
        );
    }

    public static ConversationContext fromSnapshot(ConversationSnapshot s) {
        return new ConversationContext(s);
    }

    // -------- Memory Compression (L3: Summary Memory) --------

    /** 判断是否需要触发 LLM 压缩摘要 */
    public boolean needsCompression() {
        return structuredTurns.size() >= 8 && (structuredTurns.size() - compressedTurnCount) >= 6;
    }

    /** 获取待压缩的最近对话（供 MemoryCompressionService 调用 LLM）*/
    public String turnsForCompression() {
        int from = Math.max(0, structuredTurns.size() - 12);
        return structuredTurns.subList(from, structuredTurns.size()).stream()
                .map(t -> t.role() + ": " + t.content())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 写入 LLM 生成的摘要，并清除已压缩的旧 turns。
     * <p>
     * 核心设计（对标 LangChain ConversationSummaryBufferMemory）：
     * <pre>
     * 压缩前：[t1][t2][t3][t4][t5][t6][t7][t8][t9][t10][t11][t12]
     * 压缩后：summary="t1-t9的摘要"  +  [t10][t11][t12]（保留最近3条原文）
     * Prompt = [summary] + [最近原文]  → 完整上下文，无重复，token最优
     * </pre>
     * 保留最近 RETAIN_TURNS_AFTER_COMPRESS 条原文的原因：
     * 摘要可能丢失最近几轮的细节（LLM生成时间有偏），
     * 保留少量最新原文作为"过渡缓冲"，确保上下文连贯。
     */
    private static final int RETAIN_TURNS_AFTER_COMPRESS = 3;

    public void updateSummary(String llmSummary) {
        this.summary = llmSummary;
        this.summaryUpdatedAt = Instant.now();

        // 清除已压缩的 structuredTurns，只保留最近 RETAIN_TURNS_AFTER_COMPRESS 条
        int keepFrom = Math.max(0, structuredTurns.size() - RETAIN_TURNS_AFTER_COMPRESS);
        List<Turn> retained = new ArrayList<>(structuredTurns.subList(keepFrom, structuredTurns.size()));
        structuredTurns.clear();
        structuredTurns.addAll(retained);

        // turns（纯文本）同步清理，保持一致
        int textKeepFrom = Math.max(0, turns.size() - RETAIN_TURNS_AFTER_COMPRESS);
        List<String> retainedText = new ArrayList<>(turns.subList(textKeepFrom, turns.size()));
        turns.clear();
        turns.addAll(retainedText);

        // 压缩水位线归零（因为 structuredTurns 已清空并重建）
        this.compressedTurnCount = 0;
    }

    // -------- 以下为原有方法，保持不变 --------

    public String sessionId() { return sessionId; }
    public SlotState slotState() { return slotState; }

    public void mergeSlots(SlotState slots) {
        this.slotState = this.slotState.merge(slots);
        recordSlots(this.slotState);
    }

    public int clarifyCount() { return clarifyCount; }
    public void increaseClarifyCount() { clarifyCount++; }
    public void resetClarifyCount() { clarifyCount = 0; pendingClarifyQuestion = null; }
    public String pendingClarifyQuestion() { return pendingClarifyQuestion; }
    public void pendingClarifyQuestion(String pendingClarifyQuestion) { this.pendingClarifyQuestion = pendingClarifyQuestion; }

    public void addTurn(String role, String content) {
        turns.add(role + ": " + content);
        structuredTurns.add(new Turn(role, content == null ? "" : content, Instant.now()));
        if (turns.size() > 20) turns.removeFirst();
        if (structuredTurns.size() > 50) structuredTurns.removeFirst();
        if ("user".equals(role) && "新会话".equals(title) && content != null && !content.isBlank()) {
            title = content.length() > 18 ? content.substring(0, 18) + "..." : content;
        }
        String turnId = "turn:" + sessionId + ":" + turns.size();
        putNode(turnId, "TURN", role + ": " + abbreviate(content, 32),
                Map.of("role", role, "content", content == null ? "" : content));
        putEdge("session:" + sessionId, turnId, "HAS_TURN", Map.of("index", turns.size()));
        if (lastTurnNodeId != null) {
            putEdge(lastTurnNodeId, turnId, "NEXT_TURN", Map.of());
        }
        lastTurnNodeId = turnId;
    }

    public String recentTurns() { return String.join("\n", turns); }
    public String title() { return title; }
    public Instant createdAt() { return createdAt; }

    public void recordRoute(RouteResult route) {
        if (route == null || route.domainCode() == null) return;
        String domainId = "domain:" + route.domainCode();
        String subDomainId = "subdomain:" + route.domainCode() + ":" + route.subDomainCode();
        putNode(domainId, "DOMAIN", route.domainName(), Map.of("domainCode", route.domainCode()));
        putNode(subDomainId, "SUB_DOMAIN", route.subDomainName(), Map.of(
                "domainCode", route.domainCode(),
                "subDomainCode", route.subDomainCode(),
                "handleMode", route.handleMode(),
                "confidence", route.confidence()
        ));
        putEdge("session:" + sessionId, domainId, "ROUTED_TO", Map.of("confidence", route.confidence()));
        putEdge(domainId, subDomainId, "HAS_SUB_DOMAIN", Map.of());
        if (lastTurnNodeId != null) {
            putEdge(lastTurnNodeId, subDomainId, "CURRENT_ROUTE",
                    Map.of("reason", route.reason() == null ? "" : route.reason()));
        }
    }

    public void recordEvidence(List<Evidence> evidenceList) {
        if (evidenceList == null) return;
        for (Evidence evidence : evidenceList) {
            String id = "evidence:" + evidence.evidenceId();
            putNode(id, evidence.type().toUpperCase(), evidence.title(), Map.of(
                    "evidenceId", evidence.evidenceId(),
                    "score", evidence.score(),
                    "content", evidence.content(),
                    "metadata", evidence.metadata()
            ));
            if (lastTurnNodeId != null) {
                putEdge(lastTurnNodeId, id, "SUPPORTED_BY", Map.of("score", evidence.score()));
            }
        }
    }

    public MemoryGraph graph() {
        return new MemoryGraph(sessionId, title, List.copyOf(nodes.values()), List.copyOf(edges));
    }

    public void currentGoal(String currentGoal) { this.currentGoal = currentGoal; }
    public void currentRoute(RouteResult currentRoute) { this.currentRoute = currentRoute; }
    public void currentPlan(ExecutionPlan currentPlan) { this.currentPlan = currentPlan; }

    public void evidencePackage(EvidencePackage evidencePackage) {
        this.evidencePackage = evidencePackage == null ? EvidencePackage.empty() : evidencePackage;
    }

    public void refreshSummaryIfNeeded() {
        if (structuredTurns.size() < 8) return;
        int uncompressed = structuredTurns.size() - compressedTurnCount;
        if (uncompressed < 6) return;
        List<Turn> source = structuredTurns.subList(Math.max(0, structuredTurns.size() - 12), structuredTurns.size());
        StringBuilder sb = new StringBuilder();
        sb.append("已确认信息: ");
        sb.append("slots=").append(slotState).append("; ");
        if (currentRoute != null) {
            sb.append("route=").append(currentRoute.domainCode()).append("/").append(currentRoute.subDomainCode()).append("; ");
        }
        sb.append("最近目标: ").append(currentGoal == null ? "未明确" : currentGoal).append("; ");
        sb.append("最近对话: ");
        for (Turn turn : source) {
            sb.append(turn.role()).append(":").append(abbreviate(turn.content(), 24)).append(" | ");
        }
        this.summary = sb.toString();
        this.summaryUpdatedAt = Instant.now();
        this.compressedTurnCount = structuredTurns.size();
    }

    public LayeredMemorySnapshot layeredSnapshot() {
        int from = Math.max(0, structuredTurns.size() - 10);
        ConversationMemory conversationMemory = new ConversationMemory(
                List.copyOf(structuredTurns.subList(from, structuredTurns.size())),
                structuredTurns.size()
        );
        WorkingMemory workingMemory = new WorkingMemory(
                slotState, currentRoute, currentPlan, pendingClarifyQuestion, clarifyCount, currentGoal
        );
        EvidenceMemory evidenceMemory = new EvidenceMemory(evidencePackage.evidence(), evidencePackage.qualityScore());
        SummaryMemory summaryMemory = new SummaryMemory(summary, summaryUpdatedAt, compressedTurnCount);
        SystemMemory systemMemory = new SystemMemory(
                "demo-user",
                List.of("payment", "trade"),
                Map.of(
                        "maxClarifyRounds", 2,
                        "toolMode", "read_only",
                        "contextPolicy", "server_keeps_full_memory_model_builds_short_prompt"
                )
        );
        return new LayeredMemorySnapshot(sessionId, conversationMemory, workingMemory, evidenceMemory, summaryMemory, systemMemory);
    }

    private void recordSlots(SlotState slots) {
        recordSlot("orderId", slots.orderId());
        recordSlot("payOrderId", slots.payOrderId());
        recordSlot("checkoutId", slots.checkoutId());
        recordSlot("env", slots.env() == null ? null : slots.env().name());
        recordSlot("errorCode", slots.errorCode());
        recordSlot("timeRange", slots.timeRange());
        recordSlot("activityId", slots.activityId());
        recordSlot("couponId", slots.couponId());
    }

    private void recordSlot(String name, String value) {
        if (value == null || value.isBlank() || "UNKNOWN".equals(value)) return;
        String id = "slot:" + sessionId + ":" + name;
        putNode(id, "SLOT", name + "=" + value, Map.of("name", name, "value", value));
        putEdge("session:" + sessionId, id, "HAS_SLOT", Map.of("name", name));
        if (lastTurnNodeId != null) {
            putEdge(lastTurnNodeId, id, "MENTIONS_SLOT", Map.of("name", name));
        }
    }

    private void putNode(String id, String type, String label, Map<String, Object> properties) {
        nodes.put(id, new MemoryNode(id, type, label, properties, Instant.now()));
    }

    private void putEdge(String from, String to, String type, Map<String, Object> properties) {
        boolean exists = edges.stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to) && e.type().equals(type));
        if (!exists) {
            edges.add(new MemoryEdge(from, to, type, properties, Instant.now()));
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
