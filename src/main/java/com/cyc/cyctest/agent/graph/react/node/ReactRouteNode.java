package com.cyc.cyctest.agent.graph.react.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.domain.DomainModels.DomainCandidate;
import com.cyc.cyctest.agent.domain.DomainRegistry;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.ReactAgentNode;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component("reactRouteNode")
public class ReactRouteNode extends ReactAgentNode {

    private final DomainRegistry domainRegistry;

    public ReactRouteNode(ChatModel chatModel, JsonSupport jsonSupport, DomainRegistry domainRegistry) {
        super(chatModel, jsonSupport);
        this.domainRegistry = domainRegistry;
    }

    @Override
    protected String buildSystemPrompt() {
        List<DomainCandidate> domains = domainRegistry.enabledDomains();
        return """
                你是领域路由专家。从候选领域中选择最合适的领域和子域，只输出 JSON。
                handleMode 只能是：knowledge_only、tool_only、knowledge_and_tool、clarify_required、unsupported。
                confidence < 0.55 时 nextNode 输出 clarify，否则输出 plan。

                候选领域：
                """ + jsonSupport.write(domains) + """

                输出 JSON 格式：
                {"domainCode":"payment","domainName":"支付域","subDomainCode":"pay_diagnosis",
                 "subDomainName":"支付失败排查","handleMode":"knowledge_and_tool",
                 "confidence":0.85,"reason":"...","nextNode":"plan"}
                """;
    }

    @Override
    protected String buildUserPrompt(OverAllState state) {
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = get(state, AgentStateKeys.SLOTS, SlotState.empty());
        return "用户输入：" + userText + "\n槽位信息：" + jsonSupport.write(slots);
    }

    @Override
    protected Map<String, Object> parseOutput(String response, OverAllState state) {
        Map<String, Object> result = new HashMap<>();
        try {
            RouteResult route = jsonSupport.readJsonObject(response, RouteResult.class);
            if (route == null || !domainRegistry.contains(route.domainCode(), route.subDomainCode())) {
                throw new IllegalStateException("无效路由结果");
            }
            String nextNode = route.confidence() < 0.55 ? "clarify" : "plan";
            result.put(AgentStateKeys.ROUTE, route);
            result.put(AgentStateKeys.NEXT_NODE, nextNode);
            result.put(AgentStateKeys.AGENT_STATE, AgentState.ROUTE.name());
            result.put(AgentStateKeys.TRACE,
                    "react-route→" + nextNode + ": domain=" + route.domainCode() + " conf=" + route.confidence());
        } catch (Exception e) {
            log.warn("ReactRouteNode parse failed: {}", e.getMessage());
            result.put(AgentStateKeys.NEXT_NODE, "clarify");
            result.put(AgentStateKeys.AGENT_STATE, AgentState.ROUTE.name());
            result.put(AgentStateKeys.TRACE, "react-route parse-error: " + e.getMessage());
        }
        return result;
    }
}
