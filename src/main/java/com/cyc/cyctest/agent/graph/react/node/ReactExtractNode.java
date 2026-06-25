package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("reactExtractNode")
public class ReactExtractNode extends ReactAgentNode {

    private static final String SYSTEM = """
            你是槽位提取专家。从用户输入中提取结构化信息并判断是否需要追问。
            只输出 JSON（不含注释、不含 markdown 代码块）：
            {
              "orderId": "订单号或空字符串",
              "payOrderId": "支付单号或空字符串",
              "env": "PROD或PRE或DAILY，默认PROD",
              "errorCode": "错误码或空字符串",
              "problemType": "DIAGNOSIS或STATUS_QUERY或KNOWLEDGE_EXPLANATION或UNKNOWN",
              "userGoal": "用户目标一句话描述",
              "specificObjectQuery": true或false,
              "missingFields": ["缺少的字段名列表，如['payOrderId']，若不缺则为空数组"],
              "confidence": 0.8,
              "needAsk": true或false,
              "clarifyQuestion": "追问内容或空字符串",
              "nextNode": "clarify或route"
            }
            specificObjectQuery=true 表示用户提供了具体业务对象ID。
            needAsk=true 时 nextNode 必须是 clarify，否则是 route。
            """;

    public ReactExtractNode(ChatModel chatModel, JsonSupport jsonSupport) {
        super(chatModel, jsonSupport);
    }

    @Override
    protected String buildSystemPrompt() { return SYSTEM; }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        return "用户输入：" + state.value(AgentStateKeys.USER_TEXT, "");
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<?, ?> json = jsonSupport.readJsonObject(response, Map.class);

            String env = str(json, "env", "PROD");
            Env envEnum;
            try { envEnum = Env.valueOf(env.toUpperCase()); }
            catch (Exception e) { envEnum = Env.PROD; }

            SlotState slots = new SlotState(
                    str(json, "orderId"), str(json, "payOrderId"), null,
                    envEnum, str(json, "errorCode"), null, null, null);

            String problemTypeStr = str(json, "problemType", "UNKNOWN");
            ProblemType probType;
            try { probType = ProblemType.valueOf(problemTypeStr); }
            catch (Exception e) { probType = ProblemType.UNKNOWN; }

            boolean specificObj = Boolean.TRUE.equals(json.get("specificObjectQuery"))
                    || !slots.payOrderId().isBlank() || !slots.orderId().isBlank();
            double confidence = json.get("confidence") instanceof Number n ? n.doubleValue() : 0.7;
            String reason = str(json, "reason", "react extract");

            @SuppressWarnings("unchecked")
            List<String> missingFields = json.get("missingFields") instanceof List<?> l
                    ? (List<String>) l : List.of();

            ClarifyLlmResult clarify = new ClarifyLlmResult(
                    probType, str(json, "userGoal"), specificObj, missingFields, confidence, reason);

            boolean needAsk = Boolean.TRUE.equals(json.get("needAsk"));
            String nextNode = needAsk ? "clarify" : "route";
            String clarifyQ = str(json, "clarifyQuestion");

            result.put(AgentStateKeys.SLOTS, slots);
            result.put(AgentStateKeys.CLARIFY, clarify);
            result.put(AgentStateKeys.NEXT_NODE, nextNode);
            result.put(AgentStateKeys.CLARIFY_QUESTION, clarifyQ);
            result.put(AgentStateKeys.AGENT_STATE, needAsk ? AgentState.CLARIFY.name() : AgentState.ROUTE.name());
            result.put(AgentStateKeys.TRACE, "react-extract→" + nextNode);
        } catch (Exception e) {
            log.warn("ReactExtractNode parse failed, fallback to route: {}", e.getMessage());
            result.put(AgentStateKeys.SLOTS, SlotState.empty());
            result.put(AgentStateKeys.NEXT_NODE, "route");
            result.put(AgentStateKeys.AGENT_STATE, AgentState.ROUTE.name());
            result.put(AgentStateKeys.TRACE, "react-extract parse-error: " + e.getMessage());
        }
        return result;
    }

    private static String str(Map<?, ?> m, String k) { return str(m, k, ""); }
    private static String str(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v != null && !v.toString().isBlank() ? v.toString() : def;
    }
}
