package com.cyc.cyctest.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentModels {
    private AgentModels() {
    }

    public enum Env {
        PROD, PRE, DAILY, UNKNOWN
    }

    public enum ProblemType {
        KNOWLEDGE_EXPLANATION, STATUS_QUERY, DIAGNOSIS, CONFIG_QUERY, UNKNOWN
    }

    public enum AgentState {
        EXTRACT, CLARIFY, WAITING_USER_INPUT, ROUTE, PLAN, EXECUTE, RERETRIEVE, SYNTHESIZE, DONE, FAILED
    }

    public enum StepType {
        KNOWLEDGE_RETRIEVE, TOOL_CALL, DOMAIN_ANALYSIS
    }

    public record SlotState(
            String orderId,
            String payOrderId,
            String checkoutId,
            Env env,
            String errorCode,
            String timeRange,
            String activityId,
            String couponId
    ) {
        public static SlotState empty() {
            return new SlotState(null, null, null, Env.UNKNOWN, null, null, null, null);
        }

        public SlotState merge(SlotState newer) {
            if (newer == null) {
                return this;
            }
            return new SlotState(
                    first(newer.orderId, orderId),
                    first(newer.payOrderId, payOrderId),
                    first(newer.checkoutId, checkoutId),
                    newer.env != null && newer.env != Env.UNKNOWN ? newer.env : env,
                    first(newer.errorCode, errorCode),
                    first(newer.timeRange, timeRange),
                    first(newer.activityId, activityId),
                    first(newer.couponId, couponId)
            );
        }

        public boolean hasObjectId() {
            return hasText(orderId) || hasText(payOrderId) || hasText(checkoutId);
        }

        public String primaryObjectId() {
            return first(payOrderId, first(orderId, checkoutId));
        }

        private static String first(String a, String b) {
            return hasText(a) ? a : b;
        }
    }

    public record ChatRequest(String sessionId, String message) {
    }

    public record ChatResponse(
            String sessionId,
            String state,
            boolean waitingUserInput,
            String clarifyQuestion,
            String resumeHint,
            String answer,
            SlotState slots,
            RouteResult route,
            List<Evidence> evidence,
            List<String> trace
    ) {
    }

    public record ClarifyLlmResult(
            ProblemType problemType,
            String userGoal,
            boolean specificObjectQuery,
            List<String> missingFields,
            double confidence,
            String reason
    ) {
        public static ClarifyLlmResult fallback(String userText, SlotState slots) {
            String text = userText == null ? "" : userText;
            ProblemType type;
            if (text.contains("为什么") || text.contains("是什么") || text.contains("流程") || text.contains("规则")) {
                type = ProblemType.KNOWLEDGE_EXPLANATION;
            } else if (text.contains("查") || text.contains("状态") || slots.hasObjectId()) {
                type = ProblemType.STATUS_QUERY;
            } else if (text.contains("失败") || text.contains("异常") || text.contains("排查")) {
                type = ProblemType.DIAGNOSIS;
            } else {
                type = ProblemType.UNKNOWN;
            }
            return new ClarifyLlmResult(type, text, slots.hasObjectId(), List.of(), 0.62, "local heuristic");
        }
    }

    public record ClarifyDecision(boolean needAsk, String question, String reason) {
        public static ClarifyDecision ready() {
            return new ClarifyDecision(false, null, "ready");
        }

        public static ClarifyDecision ask(String question, String reason) {
            return new ClarifyDecision(true, question, reason);
        }
    }

    public record RouteResult(
            String domainCode,
            String domainName,
            String subDomainCode,
            String subDomainName,
            String handleMode,
            double confidence,
            String reason
    ) {
    }

    public record PlanStep(
            String stepId,
            StepType type,
            String toolCode,
            String query,
            List<String> dependsOn,
            boolean required
    ) {
    }

    public record ExecutionPlan(List<PlanStep> steps) {
    }

    public record Evidence(
            String evidenceId,
            String type,
            String title,
            String content,
            double score,
            Map<String, Object> metadata
    ) {
    }

    public record EvidencePackage(List<Evidence> evidence, double qualityScore) {
        public static EvidencePackage empty() {
            return new EvidencePackage(List.of(), 0);
        }

        public String summary() {
            if (evidence == null || evidence.isEmpty()) {
                return "no evidence";
            }
            StringBuilder sb = new StringBuilder();
            for (Evidence item : evidence) {
                sb.append(item.evidenceId()).append(": ").append(item.title()).append(";");
            }
            return sb.toString();
        }
    }

    public record AgentRunContext(
            String sessionId,
            String userText,
            SlotState slots,
            ClarifyLlmResult clarify,
            RouteResult route,
            ExecutionPlan plan,
            EvidencePackage evidence,
            AgentState state,
            int retryCount,
            String finalAnswer,
            String clarifyQuestion,
            List<String> trace
    ) {
        public AgentRunContext withState(AgentState next, String traceItem) {
            List<String> nextTrace = new java.util.ArrayList<>(trace == null ? List.of() : trace);
            nextTrace.add(Instant.now() + " " + state + " -> " + next + " " + traceItem);
            return new AgentRunContext(sessionId, userText, slots, clarify, route, plan, evidence, next,
                    retryCount, finalAnswer, clarifyQuestion, List.copyOf(nextTrace));
        }
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
