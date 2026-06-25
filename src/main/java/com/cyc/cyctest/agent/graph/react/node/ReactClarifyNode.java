package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component("reactClarifyNode")
public class ReactClarifyNode extends ReactAgentNode {

    private static final String SYSTEM = """
            你是追问专家。根据缺失信息，用一句简洁的中文向用户提出追问。
            直接输出追问内容，不加任何前缀或解释。
            """;

    public ReactClarifyNode(ChatModel chatModel, JsonSupport jsonSupport) {
        super(chatModel, jsonSupport);
    }

    @Override
    protected String buildSystemPrompt() { return SYSTEM; }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        ClarifyLlmResult clarify = get(state, AgentStateKeys.CLARIFY, null);
        List<String> missingFields = clarify != null ? clarify.missingFields() : List.of();
        String missing = missingFields.isEmpty() ? "关键业务单号" : String.join("、", missingFields);
        return "用户说：" + userText + "\n\n缺少的信息：" + missing + "\n请生成追问语句。";
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        String question = response != null ? response.trim() : "请提供更多信息以便排查。";
        return Map.of(
                AgentStateKeys.CLARIFY_QUESTION, question,
                AgentStateKeys.WAITING, true,
                AgentStateKeys.AGENT_STATE, AgentState.WAITING_USER_INPUT.name(),
                AgentStateKeys.TRACE, "react-clarify: generated question"
        );
    }
}
