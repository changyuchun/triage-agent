package com.cyc.cyctest.agent.graph;

/**
 * Agent StateGraph 中 OverAllState 的 key 常量。
 * 每个 key 对应一个状态槽，由 ReplaceStrategy / AppendStrategy 控制合并方式。
 */
public interface AgentStateKeys {

    // ---- 输入 ----
    String SESSION_ID      = "sessionId";
    String USER_TEXT       = "userText";

    // ---- 路由控制 ----
    String NEXT_NODE        = "next_node";       // String（节点名，条件边读取决定下一跳）

    // ---- 槽位与意图 ----
    String SLOTS            = "slots";           // AgentModels.SlotState
    String CLARIFY          = "clarify";         // AgentModels.ClarifyLlmResult
    String CLARIFY_QUESTION = "clarifyQuestion"; // String

    // ---- 路由与计划 ----
    String ROUTE           = "route";           // AgentModels.RouteResult
    String PLAN            = "plan";            // AgentModels.ExecutionPlan

    // ---- 执行结果 ----
    String EVIDENCE        = "evidence";        // AgentModels.EvidencePackage
    String QUALITY_SCORE   = "qualityScore";    // Double
    String RETRY_COUNT     = "retryCount";      // Integer

    // ---- 输出 ----
    String ANSWER          = "answer";          // String
    String WAITING         = "waiting";         // Boolean（等待用户输入）
    String AGENT_STATE     = "agentState";      // String（枚举名）

    // ---- 追踪 ----
    String TRACE           = "trace";           // List<String>（AppendStrategy）
}
