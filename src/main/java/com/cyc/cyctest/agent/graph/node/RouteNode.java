package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
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
 * 输出 State keys：{@link AgentStateKeys#ROUTE}、{@link AgentStateKeys#NEXT_NODE}
 * <p>
 * 当 handleMode=clarify_required 时，节点写入 NEXT_NODE="clarify"，
 * 出边（nextNodeEdge）直接读取该值路由到 clarify 节点。
 */
@Component
public class RouteNode {

    private final DomainRouter domainRouter;
    private final MemoryStore memStore;

    public RouteNode(DomainRouter domainRouter, MemoryStore memStore) {
        this.domainRouter = domainRouter;
        this.memStore = memStore;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String userText = state.value(AgentStateKeys.USER_TEXT, "");
        SlotState slots = state.value(AgentStateKeys.SLOTS, SlotState.empty());
        ClarifyLlmResult clarify = getOrDefault(state, AgentStateKeys.CLARIFY,
                ClarifyLlmResult.fallback(userText, slots));

        RouteResult route = domainRouter.route(userText, clarify, slots);
        ConversationContext memory = memStore.load(sessionId);
        memory.currentRoute(route);

        Map<String, Object> result = new HashMap<>();
        result.put(AgentStateKeys.ROUTE, route);

        if ("clarify_required".equals(route.handleMode())) {
            String q = "这个问题可能涉及多个领域，请确认你要排查的是支付、交易还是营销？";
            result.put(AgentStateKeys.NEXT_NODE, "clarify");
            result.put(AgentStateKeys.CLARIFY_QUESTION, q);
            result.put(AgentStateKeys.AGENT_STATE, AgentState.CLARIFY.name());
            result.put(AgentStateKeys.TRACE,
                    "route→clarify: low_confidence=" + route.confidence());
            // pendingClarifyQuestion 和 increaseClarifyCount 由 ClarifyNode 统一负责，此处不重复
        } else {
            result.put(AgentStateKeys.NEXT_NODE, "plan");
            result.put(AgentStateKeys.AGENT_STATE, AgentState.PLAN.name());
            result.put(AgentStateKeys.TRACE,
                    "route→plan: " + route.domainCode() + "/" + route.subDomainCode()
                            + " confidence=" + route.confidence());
        }
        memStore.save(memory);  // 两个分支统一持久化 currentRoute
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrDefault(OverAllState state, String key, T defaultValue) {
        return (T) state.value(key).orElse(defaultValue);
    }
}
