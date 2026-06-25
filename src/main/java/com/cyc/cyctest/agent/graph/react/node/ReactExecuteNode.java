package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.graph.react.skill.CompositeSkillRegistry;
import com.cyc.cyctest.agent.graph.react.skill.ReadSkillTool;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.mcp.ToolCallbackAdapter;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具执行节点（ReactAgent 版）。
 * ChatClient + tools 实现 ReAct 自主循环：推理→调工具→再推理→直到给出最终分析。
 */
@Component("reactExecuteNode")
public class ReactExecuteNode extends ReactAgentNode {

    private final ToolCallbackAdapter toolCallbacks;
    private final ReadSkillTool readSkillTool;
    private final CompositeSkillRegistry compositeSkillRegistry;
    private final SkillRegistry skillRegistry;
    private final AgentProperties agentProperties;

    public ReactExecuteNode(ChatModel chatModel, JsonSupport jsonSupport,
                            ToolCallbackAdapter toolCallbacks,
                            ReadSkillTool readSkillTool,
                            CompositeSkillRegistry compositeSkillRegistry,
                            SkillRegistry skillRegistry,
                            AgentProperties agentProperties) {
        super(chatModel, jsonSupport);
        this.toolCallbacks = toolCallbacks;
        this.readSkillTool = readSkillTool;
        this.compositeSkillRegistry = compositeSkillRegistry;
        this.skillRegistry = skillRegistry;
        this.agentProperties = agentProperties;
    }

    @Override
    protected String buildSystemPrompt() {
        return """
                你是排查专家。可以调用工具收集证据。
                先调用 read_skill 加载相关技能的排查流程，再按 tool_flow 依次调用工具，最后给出根因和处置建议。
                """;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        RouteResult route = get(state, AgentStateKeys.ROUTE, null);
        SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
        String userText = state.value(AgentStateKeys.USER_TEXT, "");

        StringBuilder sb = new StringBuilder("用户问题：").append(userText).append("\n");
        if (route != null) {
            sb.append("领域：").append(route.domainCode()).append("/").append(route.subDomainCode()).append("\n");
            skillRegistry.findByDomain(route.domainCode(), route.subDomainCode())
                    .forEach(m -> sb.append("可用技能：").append(m.name()).append("\n"));
        }
        sb.append("槽位：payOrderId=").append(nvl(slots.payOrderId()))
          .append(" orderId=").append(nvl(slots.orderId()))
          .append(" env=").append(slots.env())
          .append(" errorCode=").append(nvl(slots.errorCode()));
        return sb.toString();
    }

    @Override
    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            int retryCount = state.value(AgentStateKeys.RETRY_COUNT, 0);
            try {
                var skillAdvisor = new com.alibaba.cloud.ai.graph.skills.SpringAiSkillAdvisor.Builder()
                        .skillRegistry(compositeSkillRegistry)
                        .build();

                String response = ChatClient.builder(chatModel)
                        .defaultAdvisors((Advisor) skillAdvisor)
                        .build()
                        .prompt()
                        .system(buildSystemPrompt())
                        .user(buildUserPrompt(state))
                        .tools(toolCallbacks, readSkillTool)
                        .call()
                        .content();

                double qualityScore = scoreResponse(response);
                boolean lowQuality = qualityScore < agentProperties.runtime().minEvidenceScore() && retryCount < 1;
                String nextNode = lowQuality ? "reretrieve" : "synthesize";

                Evidence ev = new Evidence("react-execute", "TOOL_CALL", "排查结果",
                        response, qualityScore, Map.of());
                EvidencePackage evidence = new EvidencePackage(List.of(ev), qualityScore);

                return Map.of(
                        AgentStateKeys.EVIDENCE, evidence,
                        AgentStateKeys.QUALITY_SCORE, qualityScore,
                        AgentStateKeys.NEXT_NODE, nextNode,
                        AgentStateKeys.AGENT_STATE, lowQuality ? AgentState.RERETRIEVE.name() : AgentState.SYNTHESIZE.name(),
                        AgentStateKeys.TRACE, "react-execute→" + nextNode + " quality=" + qualityScore
                );
            } catch (Exception e) {
                log.error("ReactExecuteNode failed: {}", e.getMessage(), e);
                Evidence ev = new Evidence("react-execute-error", "TOOL_CALL", "执行失败",
                        e.getMessage(), 0.0, Map.of());
                return Map.of(
                        AgentStateKeys.EVIDENCE, new EvidencePackage(List.of(ev), 0.0),
                        AgentStateKeys.QUALITY_SCORE, 0.0,
                        AgentStateKeys.NEXT_NODE, retryCount < 1 ? "reretrieve" : "synthesize",
                        AgentStateKeys.AGENT_STATE, AgentState.FAILED.name(),
                        AgentStateKeys.TRACE, "react-execute error: " + e.getMessage()
                );
            }
        });
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        return Map.of(); // 由 action() 直接处理
    }

    private double scoreResponse(String resp) {
        if (resp == null || resp.isBlank()) return 0.1;
        if (resp.length() > 200) return 0.9;
        if (resp.length() > 50) return 0.7;
        return 0.4;
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
