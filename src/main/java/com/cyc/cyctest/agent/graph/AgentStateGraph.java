package com.cyc.cyctest.agent.graph;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
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
            // 路由控制（节点写入，条件边读取）
            state.put(AgentStateKeys.NEXT_NODE,        new ReplaceStrategy());
            // 槽位与意图
            state.put(AgentStateKeys.SLOTS,            new ReplaceStrategy());
            state.put(AgentStateKeys.CLARIFY,          new ReplaceStrategy());
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
        // 节点在 NEXT_NODE 写入目标，条件边直接读取，无路由逻辑
        graph.addConditionalEdges("extract",
                nextNodeEdge("route"),
                Map.of("clarify", "clarify", "route", "route"));

        // clarify → END（本轮结束，等待用户下一轮输入）
        graph.addEdge("clarify", StateGraph.END);

        // route → clarify（路由置信度不足）| plan（正常规划）
        graph.addConditionalEdges("route",
                nextNodeEdge("plan"),
                Map.of("clarify", "clarify", "plan", "plan"));

        // plan → execute（无条件）
        graph.addEdge("plan", "execute");

        // execute → reretrieve（质量不足 && 未重试）| synthesize（质量达标）
        graph.addConditionalEdges("execute",
                nextNodeEdge("synthesize"),
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
    // 3. 条件边（Edge Actions）：统一从 NEXT_NODE 读取节点名
    //    路由逻辑由各节点在执行时写入 NEXT_NODE，条件边只做传递，不含业务判断。
    // =========================================================================

    /**
     * 通用条件边：读取节点写入的 {@link AgentStateKeys#NEXT_NODE} 决定下一跳。
     *
     * @param defaultTarget 当 NEXT_NODE 未写入时的保底节点名
     */
    private AsyncEdgeAction nextNodeEdge(String defaultTarget) {
        return AsyncEdgeAction.edge_async(state ->
                (String) state.value(AgentStateKeys.NEXT_NODE).orElse(defaultTarget));
    }
}
