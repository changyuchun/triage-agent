package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.*;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.MemoryStore;
import com.cyc.cyctest.agent.slot.SlotExtractionService;
import com.cyc.cyctest.agent.core.ClarifyService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 槽位提取节点（Graph 中第一个节点）。
 * <p>
 * 职责：
 * 1. 从用户输入中提取结构化槽位（orderId、payOrderId、env、errorCode 等）
 * 2. 分析用户意图（problemType、userGoal）
 * 3. 判断是否需要追问（needClarify）
 * <p>
 * 输出 State keys：{@link AgentStateKeys#SLOTS}、{@link AgentStateKeys#CLARIFY}、
 * {@link AgentStateKeys#NEED_CLARIFY}、{@link AgentStateKeys#CLARIFY_QUESTION}
 */
@Component
public class ExtractNode {

    private final SlotExtractionService slotSvc;
    private final ClarifyService clarifySvc;
    private final MemoryStore memStore;

    public ExtractNode(SlotExtractionService slotSvc,
                       ClarifyService clarifySvc,
                       MemoryStore memStore) {
        this.slotSvc = slotSvc;
        this.clarifySvc = clarifySvc;
        this.memStore = memStore;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String userText = state.value(AgentStateKeys.USER_TEXT, "");

        ConversationContext memory = memStore.load(sessionId);
        SlotState slots = slotSvc.extractAndMerge(userText, memory);
        ClarifyLlmResult clarify = clarifySvc.analyze(userText, memory, slots);
        memory.currentGoal(clarify.userGoal());
        ClarifyDecision decision = clarifySvc.decide(clarify, slots, memory);

        Map<String, Object> result = new HashMap<>();
        result.put(AgentStateKeys.SLOTS, slots);
        result.put(AgentStateKeys.CLARIFY, clarify);
        result.put(AgentStateKeys.NEED_CLARIFY, decision.needAsk());
        result.put(AgentStateKeys.CLARIFY_QUESTION, decision.needAsk() ? decision.question() : "");
        result.put(AgentStateKeys.AGENT_STATE,
                decision.needAsk() ? AgentState.CLARIFY.name() : AgentState.ROUTE.name());
        result.put(AgentStateKeys.TRACE,
                "extract→" + (decision.needAsk() ? "clarify" : "route") + ": " + decision.reason());
        return result;
    }
}
