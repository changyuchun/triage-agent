package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 答案合成节点（ReactAgent 版）。
 * 与经典版 AnswerSynthesizer 对齐：SOP 预注入 system prompt（不再依赖 LLM 主动调 read_skill），
 * evidence 结构化写入 user prompt，LLM 按 SOP 解读证据生成最终答案。
 */
@Component("reactSynthesizeNode")
public class ReactSynthesizeNode extends ReactAgentNode {

    private final SkillRegistry skillRegistry;

    public ReactSynthesizeNode(ChatModel chatModel, JsonSupport jsonSupport,
                               SkillRegistry skillRegistry) {
        super(chatModel, jsonSupport);
        this.skillRegistry = skillRegistry;
    }

    @Override
    protected String buildSystemPrompt() {
        return "你是排查答案合成专家。根据证据和领域 SOP，给出清晰的根因分析和处置建议。回答结构：1.根因 2.排查路径 3.处置建议。";
    }

    private String buildSystemPrompt(String sop) {
        String sopSection = sop == null || sop.isBlank() ? ""
                : "\n\n领域诊断 SOP（严格按此解读证据，不得偏离）：\n" + sop;
        return "你是排查答案合成专家。根据证据和领域 SOP，给出清晰的根因分析和处置建议。" +
               "回答结构：1.根因 2.排查路径 3.处置建议。严格基于证据回答，不要编造工具没有查到的事实。" +
               sopSection;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
        RouteResult route = get(state, AgentStateKeys.ROUTE, null);
        EvidencePackage evidence = get(state, AgentStateKeys.EVIDENCE, EvidencePackage.empty());

        StringBuilder sb = new StringBuilder("用户问题：").append(userText).append("\n\n");
        if (route != null) {
            sb.append("领域：").append(route.domainCode()).append("/").append(route.subDomainCode()).append("\n");
        }
        sb.append("槽位：payOrderId=").append(nvl(slots.payOrderId()))
          .append(" orderId=").append(nvl(slots.orderId())).append("\n\n");
        sb.append("证据（").append(evidence.evidence().size()).append("条）：\n");
        evidence.evidence().forEach(e ->
                sb.append("- [").append(e.evidenceId()).append("] ").append(e.title())
                  .append(": ").append(e.content()).append("\n"));
        return sb.toString();
    }

    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            try {
                RouteResult route = get(state, AgentStateKeys.ROUTE, null);
                String sop = route != null
                        ? skillRegistry.sopFor(route.domainCode(), route.subDomainCode())
                        : "";

                String answer = ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(buildSystemPrompt(sop))
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
