package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.core.DomainRouter;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 领域路由节点：将用户意图路由到具体领域（支付/交易/营销）和子领域。
 * <p>
 * 双模式：LLM 路由（置信度高时）+ 规则路由（降级）。
 * <p>
 * 死循环防护：当 clarifyCount 已达 maxClarifyRounds 上限时，即使路由置信度不足，
 * 也强制将 handleMode 改为 knowledge_and_tool 继续向下执行，避免
 * ROUTE → CLARIFY → EXTRACT → ROUTE 的无限循环。
 * <p>
 * 输出 State keys：{@link AgentStateKeys#ROUTE}、{@link AgentStateKeys#NEXT_NODE}
 */
@Component
public class RouteNode {

    private final DomainRouter domainRouter;
    private final MemoryStore memStore;
    private final AgentProperties properties;

    public RouteNode(DomainRouter domainRouter, MemoryStore memStore, AgentProperties properties) {
        this.domainRouter = domainRouter;
        this.memStore = memStore;
        this.properties = properties;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String userText  = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots  = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        ClarifyLlmResult clarify = getOrDefault(state, AgentStateKeys.CLARIFY,
                ClarifyLlmResult.fallback(userText, slots));

        RouteResult route = domainRouter.route(userText, clarify, slots);
        ConversationContext memory = memStore.load(sessionId);
        memory.currentRoute(route);

        Map<String, Object> result = new HashMap<>();

        if ("clarify_required".equals(route.handleMode())) {
            // 澄清次数达到上限：强制放行，避免死循环
            if (memory.clarifyCount() >= properties.runtime().maxClarifyRounds()) {
                RouteResult forced = new RouteResult(
                        route.domainCode(), route.domainName(),
                        route.subDomainCode(), route.subDomainName(),
                        "knowledge_and_tool", route.confidence(),
                        "forced: max clarify rounds reached");
                result.put(AgentStateKeys.ROUTE, forced);
                result.put(AgentStateKeys.NEXT_NODE, "plan");
                result.put(AgentStateKeys.AGENT_STATE, AgentState.PLAN.name());
                result.put(AgentStateKeys.TRACE,
                        "route→plan(forced): clarifyCount=" + memory.clarifyCount()
                                + " maxRounds=" + properties.runtime().maxClarifyRounds());
            } else {
                // 正常澄清分支
                String q = "这个问题可能涉及多个领域，请确认你要排查的是支付、交易还是营销？";
                result.put(AgentStateKeys.ROUTE, route);
                result.put(AgentStateKeys.NEXT_NODE, "clarify");
                result.put(AgentStateKeys.CLARIFY_QUESTION, q);
                result.put(AgentStateKeys.AGENT_STATE, AgentState.CLARIFY.name());
                result.put(AgentStateKeys.TRACE,
                        "route→clarify: low_confidence=" + route.confidence()
                                + " clarifyCount=" + memory.clarifyCount());
            }
        } else {
            result.put(AgentStateKeys.ROUTE, route);
            result.put(AgentStateKeys.NEXT_NODE, "plan");
            result.put(AgentStateKeys.AGENT_STATE, AgentState.PLAN.name());
            result.put(AgentStateKeys.TRACE,
                    "route→plan: " + route.domainCode() + "/" + route.subDomainCode()
                            + " confidence=" + route.confidence());
        }

        memStore.save(memory);
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
