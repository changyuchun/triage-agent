package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.graph.react.skill.CompositeSkillRegistry;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component("reactSynthesizeNode")
public class ReactSynthesizeNode extends ReactAgentNode {

    private final CompositeSkillRegistry compositeSkillRegistry;

    public ReactSynthesizeNode(ChatModel chatModel, JsonSupport jsonSupport,
                               CompositeSkillRegistry compositeSkillRegistry) {
        super(chatModel, jsonSupport);
        this.compositeSkillRegistry = compositeSkillRegistry;
    }

    @Override
    protected String buildSystemPrompt() {
        return """
                你是排查答案合成专家。根据证据包和领域 SOP，给出清晰的根因分析和处置建议。
                回答结构：1.根因 2.排查路径 3.处置建议。
                如技能列表中有相关技能，先调用 read_skill 加载 SOP。
                """;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route = get(state, AgentStateKeys.ROUTE, null);
        EvidencePackage evidence = get(state, AgentStateKeys.EVIDENCE, EvidencePackage.empty());

        StringBuilder sb = new StringBuilder("用户问题：").append(userText).append("\n\n");
        if (route != null) sb.append("领域：").append(route.domainCode()).append("/").append(route.subDomainCode()).append("\n");
        sb.append("槽位：payOrderId=").append(nvl(slots.payOrderId()))
          .append(" orderId=").append(nvl(slots.orderId())).append("\n\n");
        sb.append("证据：\n");
        evidence.evidence().forEach(e -> sb.append("- ").append(e.title()).append(": ").append(e.content()).append("\n"));
        return sb.toString();
    }

    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            try {
                var skillAdvisor = new com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor.Builder()
                        .skillRegistry(compositeSkillRegistry)
                        .build();

                String answer = ChatClient.builder(chatModel)
                        .defaultAdvisors((Advisor) skillAdvisor)
                        .build()
                        .prompt()
                        .system(buildSystemPrompt())
                        .user(buildUserPrompt(state))
                        .call()
                        .content();

                return Map.of(
                        AgentStateKeys.ANSWER, answer != null ? answer : "暂无分析结果",
                        AgentStateKeys.WAITING, false,
                        AgentStateKeys.AGENT_STATE, AgentState.DONE.name(),
                        AgentStateKeys.TRACE, "react-synthesize: done"
                );
            } catch (Exception e) {
                log.error("ReactSynthesizeNode failed: {}", e.getMessage(), e);
                return Map.of(
                        AgentStateKeys.ANSWER, "答案合成失败，请重试。",
                        AgentStateKeys.AGENT_STATE, AgentState.FAILED.name(),
                        AgentStateKeys.TRACE, "react-synthesize error: " + e.getMessage()
                );
            }
        });
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        return Map.of();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
