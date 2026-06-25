package com.cyc.cyctest.agent.graph.react;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.cyc.cyctest.agent.graph.AgentStateKeys;
import com.cyc.cyctest.agent.graph.react.node.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ReactAgent Graph 装配（平行于现有 AgentStateGraph，Bean 名称加 reactAgent 前缀，互不冲突）。
 * 拓扑与现有图完全一致：7 节点 + 相同条件路由。
 */
@Configuration
public class ReactAgentStateGraph {

    @Bean("reactAgentKeyStrategyFactory")
    public KeyStrategyFactory reactAgentKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> state = new LinkedHashMap<>();
            state.put(AgentStateKeys.SESSION_ID,       new ReplaceStrategy());
            state.put(AgentStateKeys.USER_TEXT,        new ReplaceStrategy());
            state.put(AgentStateKeys.NEXT_NODE,        new ReplaceStrategy());
            state.put(AgentStateKeys.SLOTS,            new ReplaceStrategy());
            state.put(AgentStateKeys.CLARIFY,          new ReplaceStrategy());
            state.put(AgentStateKeys.CLARIFY_QUESTION, new ReplaceStrategy());
            state.put(AgentStateKeys.ROUTE,            new ReplaceStrategy());
            state.put(AgentStateKeys.PLAN,             new ReplaceStrategy());
            state.put(AgentStateKeys.EVIDENCE,         new ReplaceStrategy());
            state.put(AgentStateKeys.QUALITY_SCORE,    new ReplaceStrategy());
            state.put(AgentStateKeys.RETRY_COUNT,      new ReplaceStrategy());
            state.put(AgentStateKeys.ANSWER,           new ReplaceStrategy());
            state.put(AgentStateKeys.WAITING,          new ReplaceStrategy());
            state.put(AgentStateKeys.AGENT_STATE,      new ReplaceStrategy());
            state.put(AgentStateKeys.TRACE,            new AppendStrategy());
            return state;
        };
    }

    @Bean("reactAgentCompiledGraph")
    public CompiledGraph reactAgentCompiledGraph(
            KeyStrategyFactory reactAgentKeyStrategyFactory,
            ReactExtractNode     reactExtractNode,
            ReactClarifyNode     reactClarifyNode,
            ReactRouteNode       reactRouteNode,
            ReactPlanNode        reactPlanNode,
            ReactExecuteNode     reactExecuteNode,
            ReactReRetrieveNode  reactReRetrieveNode,
            ReactSynthesizeNode  reactSynthesizeNode) throws GraphStateException {

        StateGraph graph = new StateGraph(reactAgentKeyStrategyFactory);

        graph.addNode("extract",    reactExtractNode.action());
        graph.addNode("clarify",    reactClarifyNode.action());
        graph.addNode("route",      reactRouteNode.action());
        graph.addNode("plan",       reactPlanNode.action());
        graph.addNode("execute",    reactExecuteNode.action());
        graph.addNode("reretrieve", reactReRetrieveNode.action());
        graph.addNode("synthesize", reactSynthesizeNode.action());

        graph.addEdge(StateGraph.START, "extract");
        graph.addConditionalEdges("extract",  nextNode("route"),     Map.of("clarify","clarify","route","route"));
        graph.addEdge("clarify", StateGraph.END);
        graph.addConditionalEdges("route",    nextNode("plan"),      Map.of("clarify","clarify","plan","plan"));
        graph.addEdge("plan", "execute");
        graph.addConditionalEdges("execute",  nextNode("synthesize"),Map.of("reretrieve","reretrieve","synthesize","synthesize"));
        graph.addEdge("reretrieve", "execute");
        graph.addEdge("synthesize", StateGraph.END);

        CompileConfig config = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build();

        return graph.compile(config);
    }

    private AsyncEdgeAction nextNode(String defaultTarget) {
        return AsyncEdgeAction.edge_async(state ->
                (String) state.value(AgentStateKeys.NEXT_NODE).orElse(defaultTarget));
    }
}
