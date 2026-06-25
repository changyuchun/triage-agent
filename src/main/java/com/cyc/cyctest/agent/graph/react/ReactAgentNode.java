package com.cyc.cyctest.agent.graph.react;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.llm.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

/**
 * ReactAgent 节点基类：用 ChatClient（Spring AI ReAct 等价实现）替代硬编码 Service 调用。
 * <p>
 * 子类只需实现三个方法：
 * - buildSystemPrompt()   — 节点角色定义 + 输出格式约束
 * - buildUserPrompt(state) — 从 OverAllState 提取输入，构造用户消息
 * - parseOutput(resp, state) — 解析 LLM 输出（JSON / 纯文本），写入 OverAllState
 * <p>
 * execute / synthesize 等需要工具或 Advisor 的节点，重写 action() 即可。
 */
public abstract class ReactAgentNode {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ChatModel chatModel;
    protected final JsonSupport jsonSupport;

    protected ReactAgentNode(ChatModel chatModel, JsonSupport jsonSupport) {
        this.chatModel = chatModel;
        this.jsonSupport = jsonSupport;
    }

    protected abstract String buildSystemPrompt();

    protected abstract String buildUserPrompt(OverAllState state);

    protected abstract Map<String, Object> parseOutput(String response, OverAllState state);

    public AsyncNodeAction action() {
        return AsyncNodeAction.node_async(state -> {
            try {
                String resp = ChatClient.create(chatModel)
                        .prompt()
                        .system(buildSystemPrompt())
                        .user(buildUserPrompt(state))
                        .call()
                        .content();
                return parseOutput(resp, state);
            } catch (Exception e) {
                log.error("[{}] ReactAgent call failed: {}", getClass().getSimpleName(), e.getMessage(), e);
                return Map.of(
                        AgentStateKeys.TRACE, getClass().getSimpleName() + " error: " + e.getMessage(),
                        AgentStateKeys.AGENT_STATE, "ERROR"
                );
            }
        });
    }

    @SuppressWarnings("unchecked")
    protected <T> T get(OverAllState state, String key, T def) {
        return (T) state.value(key).orElse(def);
    }
}
