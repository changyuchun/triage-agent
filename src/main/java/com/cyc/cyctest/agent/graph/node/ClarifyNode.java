package com.cyc.cyctest.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.core.AgentModels.AgentState;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.memory.ConversationContext;
import com.cyc.cyctest.agent.memory.MemoryStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 澄清节点：将本轮 Agent 状态置为"等待用户输入"。
 * <p>
 * 触发条件：extract 节点分析后认为槽位不足，或 route 节点返回 clarify_required。
 * <p>
 * 当此节点执行后，Graph 到达 END，本轮 HTTP 响应返回 waitingUserInput=true。
 * 用户下一轮携带相同 sessionId 发消息后，Graph 从 extract 重新进入，
 * 延续上一轮已提取的槽位（通过 ConversationContext 中的 slotState）。
 */
@Component
public class ClarifyNode {

    private final MemoryStore memStore;

    public ClarifyNode(MemoryStore memStore) {
        this.memStore = memStore;
    }

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(this::process);
    }

    private Map<String, Object> process(OverAllState state) {
        String sessionId = state.value(AgentStateKeys.SESSION_ID, "");
        String question = state.value(AgentStateKeys.CLARIFY_QUESTION, "请补充更多信息");

        ConversationContext memory = memStore.load(sessionId);
        memory.increaseClarifyCount();
        memory.pendingClarifyQuestion(question);
        memStore.save(memory);

        return Map.of(
                AgentStateKeys.WAITING, true,
                AgentStateKeys.ANSWER, question,
                AgentStateKeys.AGENT_STATE, AgentState.WAITING_USER_INPUT.name(),
                AgentStateKeys.TRACE, "clarify: waiting_user_input");
    }
}
