package com.cyc.cyctest.agent.graph;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.cyc.cyctest.agent.config.AgentProperties;
import com.cyc.cyctest.agent.graph.node.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent StateGraph 装配配置（Spring AI Alibaba LangGraph 风格）。
 * <p>
 * 本类只做三件事：
 * <ol>
 *   <li><b>State 定义</b>：声明所有状态 key 及其合并策略（{@link KeyStrategyFactory}）</li>
 *   <li><b>Node 注册</b>：将各 {@code @Component} 节点装配进图</li>
 *   <li><b>Edge 定义</b>：声明有向边和条件边，决定节点执行顺序</li>
 * </ol>
 * <p>
 * 图拓扑：
 * <pre>
 *                          ┌─ clarify ──► END
 *  START ──► extract ──────┤
 *                          └─ route ────┬─ clarify ──► END
 *                                       └─ plan ──► execute ─┬─ reretrieve ─┐
 *                                                             │              │
 *                                                             └─ synthesize ─┘
 *                                                                     │
 *                                                                    END
 * </pre>
 * 业务逻辑封装在各独立节点（{@code agent/graph/node/} 包下），本类不含任何业务代码。
 */
@Configuration
public class AgentStateGraph {

    // =========================================================================
    // 1. State 定义：声明 OverAllState 中每个 key 的合并策略
    //    ReplaceStrategy  → 新值覆盖旧值（大多数字段）
    //    AppendStrategy   → 新值追加到列表（trace 追踪链路）
    // =========================================================================
    @Bean
    public KeyStrategyFactory agentKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> state = new LinkedHashMap<>();
            // 输入
            state.put(AgentStateKeys.SESSION_ID,       new ReplaceStrategy());
            state.put(AgentStateKeys.USER_TEXT,        new ReplaceStrategy());
            // 槽位与意图
            state.put(AgentStateKeys.SLOTS,            new ReplaceStrategy());
            state.put(AgentStateKeys.CLARIFY,          new ReplaceStrategy());
            state.put(AgentStateKeys.NEED_CLARIFY,     new ReplaceStrategy());
            state.put(AgentStateKeys.CLARIFY_QUESTION, new ReplaceStrategy());
            // 路由与计划
            state.put(AgentStateKeys.ROUTE,            new ReplaceStrategy());
            state.put(AgentStateKeys.PLAN,             new ReplaceStrategy());
            // 执行结果
            state.put(AgentStateKeys.EVIDENCE,         new ReplaceStrategy());
            state.put(AgentStateKeys.QUALITY_SCORE,    new ReplaceStrategy());
            state.put(AgentStateKeys.RETRY_COUNT,      new ReplaceStrategy());
            // 输出
            state.put(AgentStateKeys.ANSWER,           new ReplaceStrategy());
            state.put(AgentStateKeys.WAITING,          new ReplaceStrategy());
            state.put(AgentStateKeys.AGENT_STATE,      new ReplaceStrategy());
            // 追踪（AppendStrategy：每个节点的 trace 自动追加为列表）
            state.put(AgentStateKeys.TRACE,            new AppendStrategy());
            return state;
        };
    }

    // =========================================================================
    // 2. Node + Edge 定义：装配完整的 StateGraph 并编译为 CompiledGraph
    // =========================================================================
    @Bean
    public CompiledGraph agentCompiledGraph(
            KeyStrategyFactory agentKeyStrategyFactory,
            AgentProperties properties,
            // 各节点 Bean（@Component，自动注入）
            ExtractNode extractNode,
            ClarifyNode clarifyNode,
            RouteNode routeNode,
            PlanNode planNode,
            ExecuteNode executeNode,
            ReRetrieveNode reRetrieveNode,
            SynthesizeNode synthesizeNode) throws GraphStateException {

        StateGraph graph = new StateGraph(agentKeyStrategyFactory);

        // ---- Node 注册 ----
        graph.addNode("extract",    extractNode.action());
        graph.addNode("clarify",    clarifyNode.action());
        graph.addNode("route",      routeNode.action());
        graph.addNode("plan",       planNode.action());
        graph.addNode("execute",    executeNode.action());
        graph.addNode("reretrieve", reRetrieveNode.action());
        graph.addNode("synthesize", synthesizeNode.action());

        // ---- Edge 定义 ----
        // 起点
        graph.addEdge(StateGraph.START, "extract");

        // extract → clarify（需要追问）| route（直接路由）
        graph.addConditionalEdges("extract",
                needClarifyEdge(),
                Map.of("clarify", "clarify", "route", "route"));

        // clarify → END（本轮结束，等待用户下一轮输入）
        graph.addEdge("clarify", StateGraph.END);

        // route → clarify（路由置信度不足）| plan（正常规划）
        graph.addConditionalEdges("route",
                routeDecisionEdge(),
                Map.of("clarify", "clarify", "plan", "plan"));

        // plan → execute（无条件）
        graph.addEdge("plan", "execute");

        // execute → reretrieve（质量不足 && 未重试）| synthesize（质量达标）
        graph.addConditionalEdges("execute",
                qualityCheckEdge(properties),
                Map.of("reretrieve", "reretrieve", "synthesize", "synthesize"));

        // reretrieve → execute（重写后重新执行，最多 1 次）
        graph.addEdge("reretrieve", "execute");

        // synthesize → END
        graph.addEdge("synthesize", StateGraph.END);

        // MemorySaver：按 threadId(=sessionId) 存储图 Checkpoint，支持多轮对话断点恢复
        CompileConfig config = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                .build();

        return graph.compile(config);
    }

    // =========================================================================
    // 3. 条件边（Edge Actions）：读取 State 决定下一跳节点
    // =========================================================================

    /** extract 节点出边：NEED_CLARIFY=true → clarify，否则 → route */
    private AsyncEdgeAction needClarifyEdge() {
        return AsyncEdgeAction.edge_async(state ->
                state.value(AgentStateKeys.NEED_CLARIFY, false) ? "clarify" : "route");
    }

    /** route 节点出边：NEED_CLARIFY=true（handleMode=clarify_required）→ clarify，否则 → plan */
    private AsyncEdgeAction routeDecisionEdge() {
        return AsyncEdgeAction.edge_async(state ->
                state.value(AgentStateKeys.NEED_CLARIFY, false) ? "clarify" : "plan");
    }

    /** execute 节点出边：质量不足且未重试 → reretrieve，否则 → synthesize */
    private AsyncEdgeAction qualityCheckEdge(AgentProperties properties) {
        return AsyncEdgeAction.edge_async(state -> {
            double score = state.value(AgentStateKeys.QUALITY_SCORE, 0.0);
            int retry   = state.value(AgentStateKeys.RETRY_COUNT, 0);
            return (score < properties.runtime().minEvidenceScore() && retry < 1)
                    ? "reretrieve" : "synthesize";
        });
    }
}
