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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClarifyService {
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;
    private final AgentProperties properties;

    public ClarifyService(LlmClient llmClient, JsonSupport jsonSupport, AgentProperties properties) {
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
        this.properties = properties;
    }

    public ClarifyLlmResult analyze(String userText, ConversationContext context, SlotState slots) {
        if (!llmClient.available()) {
            return ClarifyLlmResult.fallback(userText, slots);
        }
        String system = "你是智能答疑 Agent 的对话挖掘模块，只输出 JSON，不直接回答用户。";
        String user = """
                判断用户问题类型、用户目标、是否是具体对象查询、缺失字段。
                problemType 只能是 KNOWLEDGE_EXPLANATION, STATUS_QUERY, DIAGNOSIS, CONFIG_QUERY, UNKNOWN。

                当前输入:
                %s

                已有槽位:
                %s

                最近对话:
                %s

                输出 JSON:
                {"problemType":"DIAGNOSIS","userGoal":"...","specificObjectQuery":true,"missingFields":["env"],"confidence":0.8,"reason":"..."}
                """.formatted(userText, slots, context.recentTurns());
        try {
            ClarifyLlmResult result = jsonSupport.readJsonObject(llmClient.complete(system, user), ClarifyLlmResult.class);
            if (result.confidence() < 0 || result.confidence() > 1 || result.problemType() == null) {
                return ClarifyLlmResult.fallback(userText, slots);
            }
            return result;
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
                return ClarifyDecision.ask("请提供订单号、支付单号或 checkoutId，我才能查询具体对象。", "missing object id");
            }
            if (slots.env() == null || slots.env() == Env.UNKNOWN) {
                return ClarifyDecision.ask("请确认查询环境：生产、预发还是日常？", "missing env");
            }
            return ClarifyDecision.ready();
        }
        if (llm.confidence() < 0.55) {
            return ClarifyDecision.ask("你是想查询具体状态，还是想了解规则/流程？", "low intent confidence");
        }
        if (llm.missingFields() != null && !llm.missingFields().isEmpty()) {
            List<String> fields = llm.missingFields();
            if (fields.contains("env")) {
                return ClarifyDecision.ask("请确认查询环境：生产、预发还是日常？", "llm missing env");
            }
        }
        return ClarifyDecision.ready();
    }
}
