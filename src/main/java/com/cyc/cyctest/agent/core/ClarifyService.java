package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.ClarifyDecision;
import com.cyc.cyctest.agent.core.AgentModels.ClarifyLlmResult;
import com.cyc.cyctest.agent.core.AgentModels.Env;
import com.cyc.cyctest.agent.core.AgentModels.ProblemType;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.slot.SlotExtractor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClarifyService {
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final AgentProperties properties;
    private final Map<String, String> promptMap;

    public ClarifyService(LlmClient llmClient, JsonSupport jsonSupport, AgentProperties properties,
                          List<SlotExtractor> extractors) {
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
        // 低优先级先放，高优先级后覆盖，确保 payment(100) > marketing(80)
        Map<String, String> merged = new HashMap<>();
        extractors.stream()
                .sorted(Comparator.comparingInt(SlotExtractor::priority))
                .forEach(e -> merged.putAll(e.clarifyPrompts()));
        this.promptMap = Map.copyOf(merged);
    }

    public ClarifyLlmResult analyze(String userText, ConversationContext context, SlotState slots) {
        if (!llmClient.available()) {
            return ClarifyLlmResult.fallback(userText, slots);
        }
        String system = """
                你是智能答疑 Agent 的对话挖掘模块，只输出 JSON，不直接回答用户。
                【强制约束】problemType 必须且只能是以下 5 个值之一，禁止输出任何其他字符串：
                KNOWLEDGE_EXPLANATION | STATUS_QUERY | DIAGNOSIS | CONFIG_QUERY | UNKNOWN""";
        String user = """
                根据当前输入和历史对话，判断用户问题类型、用户目标、是否是具体对象查询、缺失字段。

                当前输入:
                %s

                已有槽位:
                %s

                最近对话:
                %s

                输出 JSON（problemType 只允许5个值，见 system 说明）:
                {"problemType":"DIAGNOSIS","userGoal":"...","specificObjectQuery":true,"missingFields":["env"],"confidence":0.8,"reason":"..."}
                """.formatted(userText, slots, context.recentTurns());
        try {
            String raw = llmClient.complete(system, user);
            try {
                ClarifyLlmResult result = jsonSupport.readJsonObject(raw, ClarifyLlmResult.class);
                if (result.confidence() < 0 || result.confidence() > 1 || result.problemType() == null) {
                    return ClarifyLlmResult.fallback(userText, slots);
                }
                return result;
            } catch (Exception e) {
                // LLM 输出了非法 problemType（如 "PAYMENTOrder"）：用 Map 宽容解析，保留 userGoal/missingFields
                return lenientParse(raw, userText, slots);
            }
        } catch (Exception e) {
            return ClarifyLlmResult.fallback(userText, slots);
        }
    }

    private static final Set<String> VALID_PROBLEM_TYPES = Set.of(
            "KNOWLEDGE_EXPLANATION", "STATUS_QUERY", "DIAGNOSIS", "CONFIG_QUERY", "UNKNOWN");

    @SuppressWarnings("unchecked")
    private ClarifyLlmResult lenientParse(String raw, String userText, SlotState slots) {
        try {
            Map<String, Object> map = jsonSupport.readJsonObject(raw, Map.class);
            String rawType = String.valueOf(map.getOrDefault("problemType", "UNKNOWN")).toUpperCase();
            ProblemType problemType = VALID_PROBLEM_TYPES.contains(rawType)
                    ? ProblemType.valueOf(rawType)
                    : ClarifyLlmResult.fallback(userText, slots).problemType();
            String userGoal = (String) map.getOrDefault("userGoal", userText);
            boolean specificObjectQuery = Boolean.TRUE.equals(map.get("specificObjectQuery"));
            Object mf = map.get("missingFields");
            List<String> missingFields = mf instanceof List<?> l
                    ? l.stream().map(Object::toString).toList() : List.of();
            double confidence = map.get("confidence") instanceof Number n ? n.doubleValue() : 0.6;
            return new ClarifyLlmResult(problemType, userGoal, specificObjectQuery, missingFields, confidence, "lenient-parse");
        } catch (Exception e) {
            return ClarifyLlmResult.fallback(userText, slots);
        }
    }

    public ClarifyDecision decide(ClarifyLlmResult llm, SlotState slots, ConversationContext context) {
        if (context.clarifyCount() >= properties.runtime().maxClarifyRounds()) {
            return ClarifyDecision.ready();
        }
        if (llm.problemType() == ProblemType.KNOWLEDGE_EXPLANATION) {
            return ClarifyDecision.ready();
        }
        if (llm.problemType() == ProblemType.STATUS_QUERY || llm.problemType() == ProblemType.DIAGNOSIS) {
            if (!slots.hasObjectId()) {
                return ClarifyDecision.ask(
                        promptMap.getOrDefault("objectId", "请提供相关 ID 以便查询具体对象。"),
                        "missing object id");
            }
            if (slots.env() == null || slots.env() == Env.UNKNOWN) {
                return ClarifyDecision.ask(
                        promptMap.getOrDefault("env", "请确认查询环境：生产、预发还是日常？"),
                        "missing env");
            }
            return ClarifyDecision.ready();
        }
        if (llm.confidence() < 0.55) {
            return ClarifyDecision.ask("你是想查询具体状态，还是想了解规则/流程？", "low intent confidence");
        }
        if (llm.missingFields() != null && !llm.missingFields().isEmpty()) {
            List<String> fields = llm.missingFields();
            if (fields.contains("env")) {
                return ClarifyDecision.ask(
                        promptMap.getOrDefault("env", "请确认查询环境：生产、预发还是日常？"),
                        "llm missing env");
            }
        }
        return ClarifyDecision.ready();
    }
}
