package com.cyc.cyctest.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client 配置：接入外部 MCP Server，将外部工具注入本 Agent 的执行能力。
 *
 * <h3>MCP 双向说明</h3>
 * <pre>
 *   MCP Server（已实现）
 *     本 Agent 暴露工具 → 外部 AI 客户端调用
 *     依赖：spring-ai-starter-mcp-server-webmvc
 *     配置：spring.ai.mcp.server.enabled=true
 *
 *   MCP Client（本配置）
 *     外部 MCP Server 暴露工具 → 本 Agent 调用
 *     依赖：spring-ai-starter-mcp-client（pom.xml 中按需开启）
 *     用途：接入 Slack / GitHub / 数据库 / 文件系统等外部 MCP 工具
 * </pre>
 *
 * <h3>启用方式</h3>
 * 1. 在 pom.xml 中打开注释（spring-ai-starter-mcp-client）
 * 2. 在 application.properties 中配置外部服务地址：
 * <pre>
 *   agent.mcp.client.enabled=true
 *
 *   # HTTP/SSE 传输（标准 MCP over HTTP）
 *   spring.ai.mcp.client.connections.filesystem.url=http://localhost:3000/mcp
 *   spring.ai.mcp.client.connections.github.url=http://localhost:3001/sse
 *
 *   # StdIO 传输（本地进程）
 *   spring.ai.mcp.client.connections.brave-search.command=npx
 *   spring.ai.mcp.client.connections.brave-search.args=-y,@modelcontextprotocol/server-brave-search
 * </pre>
 *
 * <h3>接入后工具如何使用</h3>
 * Spring AI 会自动将外部 MCP 工具包装为 {@code ToolCallback}，可以：
 * <pre>
 *   // 1. 注入到 ChatClient，让 LLM 自主选用（ReAct 模式）
 *   &#64;Autowired List&lt;ToolCallback&gt; mcpToolCallbacks;  // Spring AI 自动注入
 *   chatClient.prompt()
 *       .user(question)
 *       .toolCallbacks(mcpToolCallbacks)   // 外部 MCP 工具 + 本地 Skill 工具
 *       .call();
 *
 *   // 2. 注入到 TaskExecutionEngine，按计划步骤调用
 *   // 在 ExecutionPlan 里新增 StepType.MCP_TOOL_CALL，
 *   // TaskExecutionEngine 通过 toolName 路由到对应 ToolCallback
 * </pre>
 *
 * <h3>为什么 MCP Client 重要（面试解释）</h3>
 * MCP 协议（Anthropic 推动）解决了 Agent 的工具生态碎片化问题：
 * <pre>
 *   过去：每个 Agent 自己对接 Slack API、GitHub API、DB，重复开发
 *   MCP：工具提供方一次实现 MCP Server，所有支持 MCP Client 的 Agent 通用
 *
 *   类比：HTTP 让所有浏览器都能访问所有网站；
 *        MCP 让所有 Agent 都能使用所有工具，无需单独集成
 *
 *   与 A2A 的区别：
 *   MCP = Agent ↔ Tool（解决工具接入）
 *   A2A = Agent ↔ Agent（解决 Agent 间协作）
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "agent.mcp.client.enabled", havingValue = "true", matchIfMissing = false)
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);

    /*
     * =====================================================================
     * 以下配置在引入 spring-ai-starter-mcp-client 依赖后生效。
     * 依赖已在 pom.xml 注释区预留，按需打开。
     * =====================================================================
     *
     * Spring AI MCP Client 自动配置原理：
     *
     * 1. McpClientAutoConfiguration 读取 spring.ai.mcp.client.connections.* 配置
     * 2. 为每个连接创建 McpSyncClient（HTTP/SSE）或 McpStdioClient（本地进程）
     * 3. 调用 client.listTools() 获取外部 MCP Server 暴露的工具列表
     * 4. 将每个外部工具包装为 ToolCallback（name/description/inputSchema 来自 MCP 协议）
     * 5. 所有 ToolCallback 注册为 Spring Bean，可通过 @Autowired List<ToolCallback> 注入
     *
     * 示例：接入文件系统 MCP Server 后，Agent 可以读写本地文件
     *
     * @Bean
     * public CommandLineRunner logMcpTools(List<ToolCallback> mcpTools) {
     *     return args -> {
     *         log.info("已接入外部 MCP 工具数量: {}", mcpTools.size());
     *         mcpTools.forEach(t -> log.info("  - {}: {}",
     *             t.getToolDefinition().name(),
     *             t.getToolDefinition().description()));
     *     };
     * }
     */
}
