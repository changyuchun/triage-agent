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

@Component("reactReRetrieveNode")
public class ReactReRetrieveNode extends ReactAgentNode {

    public ReactReRetrieveNode(ChatModel chatModel, JsonSupport jsonSupport) {
        super(chatModel, jsonSupport);
    }

    @Override
    protected String buildSystemPrompt() {
        return """
                你是检索优化专家。分析当前证据质量不足的原因，基于已有信息给出补充分析。
                直接输出改进后的分析内容，不需要 JSON 格式。
                """;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        EvidencePackage evidence = get(state, AgentStateKeys.EVIDENCE, EvidencePackage.empty());
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);
        String prevContent = evidence.evidence().isEmpty() ? "无" :
                evidence.evidence().get(0).content();
        return "原始问题：" + userText + "\n当前证据质量不足（score=" + evidence.qualityScore() +
               ", retry=" + retryCount + "）\n已有信息：" + prevContent + "\n请补充分析。";
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);
        Evidence ev = new Evidence("re-retrieve", "TOOL_CALL", "补充分析", response, 0.75, Map.of());
        EvidencePackage refined = new EvidencePackage(List.of(ev), 0.75);
        return Map.of(
                AgentStateKeys.EVIDENCE, refined,
                AgentStateKeys.RETRY_COUNT, retryCount + 1,
                AgentStateKeys.AGENT_STATE, AgentState.RERETRIEVE.name(),
                AgentStateKeys.TRACE, "react-reretrieve: retry=" + (retryCount + 1)
        );
    }
}
