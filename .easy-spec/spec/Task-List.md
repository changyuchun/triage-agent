# ReactAgent Graph Runtime — 开发任务清单

> 执行原则：不修改现有代码，全部新建在 `agent/graph/react/` 子包下。
> 任务按依赖顺序排列，串行执行；T12–T14 可在 T01–T11 完成后并行。

---

## 第一组：基础结构

### T01 · 新建 react 子包 + ReactAgentNode 抽象基类

**目标**：建立所有节点的公共骨架

**交付内容**：
- `agent/graph/react/ReactAgentNode.java`（抽象类）
  - 持有 `ReactAgent reactAgent`（由子类构造函数注入）
  - 抽象方法：`buildSystemPrompt()`, `buildUserPrompt(OverAllState)`, `parseOutput(String, OverAllState)`
  - `action()` 实现：调用 `reactAgent.call(prompt, threadId=sessionId)` → `parseOutput()` → 返回 Map
  - 超时 / 解析异常兜底：TRACE 记录，AGENT_STATE=ERROR，返回安全空 Map
- `agent/graph/react/` 包目录（可只建 Java 文件，包目录自动生成）

**完成标准**：编译通过，`ReactAgentNode.action()` 返回 `AsyncNodeAction` 类型

**前置依赖**：无

---

### T02 · CompositeSkillRegistry（双层 Skill 注册表）

**目标**：合并 Classpath + FileSystem 两层 Skill，同名 FileSystem 覆盖

**交付内容**：
- `agent/graph/react/skill/CompositeSkillRegistry.java`
  - 持有 `ClasspathSkillRegistry`（加载 `classpath:skills/`）
  - 持有 `FileSystemSkillRegistry`（加载 `./skills/`，`autoReload=true`，轮询 5s）
  - `listAll()` → 合并两层，同名时 filesystem 条目覆盖 classpath
  - `findByName(String)` → 优先 filesystem，fallback classpath
  - `readContent(String)` → 返回完整 SKILL.md 文本
- `agent/graph/react/skill/SkillEntry.java`（record，含 name/description/domain/subDomain/source/content）

**完成标准**：
- 测试：classpath skills 全部可列出
- 测试：在 `./skills/` 新建同名 SKILL.md 后 5s 内 `findByName` 返回 filesystem 版本
- 测试：删除 filesystem 版本后恢复 classpath 版本

**前置依赖**：无

---

### T03 · ReactAgentSkillBridge（现有 SkillRegistry → SAA SkillsAgentHook 适配）

**目标**：将现有 SkillRegistry（自研）的输出桥接为 Spring AI Alibaba SkillsAgentHook 所需格式

**交付内容**：
- `agent/graph/react/skill/ReactAgentSkillBridge.java`（@Component）
  - 注入现有 `SkillRegistry`（自研）
  - `buildSkillsAgentHook(String domain, String subDomain)` → 返回 `SkillsAgentHook`
    - 以 `CompositeSkillRegistry.listAll()` 中与 domain/subDomain 匹配的 Skill 为列表
    - `read_skill(name)` 调用时返回对应 SKILL.md 完整内容（frontmatter + body）
  - `buildGroupedTools(String domain, String subDomain)` → 返回 `Map<String, List<ToolCallback>>`
    - 遍历匹配 Skill 的 `tool_flow`，从 `SkillRegistry.toolFor(toolCode)` 获取 AiTool，包装为 ToolCallback

**完成标准**：
- 给定 domain=payment / subDomain=pay_diagnosis，能返回包含 payment-diagnosis 和 log-query 两个 Skill 的 Hook
- `read_skill("payment-diagnosis")` 调用后返回完整 SKILL.md 内容
- groupedTools 中 payment-diagnosis key 对应 `[payment_query_tool, log_query_tool]`

**前置依赖**：T02

---

## 第二组：ReactAgent 节点实现

### T04 · ReactExtractNode（槽位提取）

**目标**：替代现有 ExtractNode（SlotExtractionService + ClarifyService），用 ReactAgent 实现

**交付内容**：
- `agent/graph/react/node/ReactExtractNode.java`（@Component，extends ReactAgentNode）
  - 构造时创建 ReactAgent：无 SkillsAgentHook，无外部 tools，纯 LLM 推理
  - `buildSystemPrompt()`：槽位提取 Agent 身份 + 输出 JSON schema 说明
  - `buildUserPrompt(state)`：拼接 userText + 历史对话摘要
  - `parseOutput()`：解析 JSON → slots/clarify/needAsk/nextNode → 写 SLOTS/CLARIFY/NEXT_NODE/CLARIFY_QUESTION/TRACE

**完成标准**：给定包含 payOrderId 的用户文本，SLOTS.payOrderId 非空，NEXT_NODE 正确（"route" 或 "clarify"）

**前置依赖**：T01

---

### T05 · ReactClarifyNode（追问生成）

**交付内容**：
- `agent/graph/react/node/ReactClarifyNode.java`（@Component，extends ReactAgentNode）
  - 构造时创建 ReactAgent：无 tools，生成追问文本
  - `buildUserPrompt(state)`：拼接缺失槽位信息 + 当前 CLARIFY 结果
  - `parseOutput()`：提取追问文本 → 写 CLARIFY_QUESTION/WAITING=true/AGENT_STATE=CLARIFY

**完成标准**：CLARIFY_QUESTION 非空，内容是自然语言追问句

**前置依赖**：T01

---

### T06 · ReactRouteNode（领域路由）

**交付内容**：
- `agent/graph/react/node/ReactRouteNode.java`（@Component，extends ReactAgentNode）
  - 注入 `DomainRegistry`，构建时将候选领域列表写入 system prompt
  - `buildUserPrompt(state)`：userText + slots + 候选领域 JSON
  - `parseOutput()`：解析 domainCode/subDomainCode/handleMode/confidence → 写 ROUTE/NEXT_NODE/TRACE
  - confidence < 0.55 时 NEXT_NODE="clarify"

**完成标准**：含"支付"关键词的输入，ROUTE.domainCode="payment"；低置信度输入 NEXT_NODE="clarify"

**前置依赖**：T01

---

### T07 · ReactPlanNode（任务规划）

**交付内容**：
- `agent/graph/react/node/ReactPlanNode.java`（@Component，extends ReactAgentNode）
  - 注入现有 `SkillRegistry`（获取 tool_flow 摘要，作为规划参考，不做工具调用）
  - `buildUserPrompt(state)`：route + slots + 对应 Skill 的 `toolFlowSummary()`
  - `parseOutput()`：解析 steps 列表 → 写 PLAN/TRACE

**完成标准**：PLAN 中包含 payment_query_step、log_query_step 等步骤（根据路由结果）

**前置依赖**：T01

---

### T08 · ReactExecuteNode（工具执行，核心节点）

**目标**：最复杂节点，集成 SkillsAgentHook + groupedTools + MCP Client 工具

**交付内容**：
- `agent/graph/react/node/ReactExecuteNode.java`（@Component，extends ReactAgentNode）
  - 注入 `ReactAgentSkillBridge`、`AgentProperties`、可选的 `List<ToolCallback> mcpToolCallbacks`
  - 构造时**不**固定 SkillsAgentHook（因为 domain/subDomain 在运行时从 state 读取）
  - `action()` 重写：
    1. 从 state 读取 ROUTE（domain/subDomain）
    2. 调用 bridge 构建 SkillsAgentHook + groupedTools
    3. 动态创建（或从缓存取）对应领域的 ReactAgent，注入 Hook + AiTool + MCP 工具
    4. 调用 ReactAgent，获取 evidence
  - 领域 ReactAgent 缓存：Map<"domain|subDomain", ReactAgent>，避免重复创建
  - `parseOutput()`：evidence + qualityScore + nextNode → 写 EVIDENCE/QUALITY_SCORE/NEXT_NODE/TRACE
  - 质量判断：`evidence.qualityScore < minEvidenceScore && retryCount < 1` → NEXT_NODE="reretrieve"

**完成标准**：
- 日志可见 `read_skill` 被调用
- `payment_query` 工具被执行，EVIDENCE 非空
- MCP tools（如已配置）出现在 ReactAgent 工具列表日志中
- qualityScore 低时 NEXT_NODE="reretrieve"

**前置依赖**：T01, T03

---

### T09 · ReactReRetrieveNode（重检索）

**交付内容**：
- `agent/graph/react/node/ReactReRetrieveNode.java`（@Component，extends ReactAgentNode）
  - 注入 KnowledgeRetriever
  - `buildUserPrompt(state)`：质量不足的 evidence + route，要求重新生成检索关键词
  - `parseOutput()`：提取精炼后的 evidence → 更新 EVIDENCE/RETRY_COUNT+1/TRACE

**完成标准**：RETRY_COUNT 自增，EVIDENCE 内容更新（或保持，不崩溃）

**前置依赖**：T01

---

### T10 · ReactSynthesizeNode（答案合成）

**交付内容**：
- `agent/graph/react/node/ReactSynthesizeNode.java`（@Component，extends ReactAgentNode）
  - 注入 `ReactAgentSkillBridge`
  - 构建 SkillsAgentHook，系统提示注入 SOP（SKILL.md body）
  - `buildUserPrompt(state)`：slots + evidence + plan + SOP（通过 read_skill 或直接从 Bridge 获取）
  - `parseOutput()`：直接取 ReactAgent 输出文本 → 写 ANSWER/AGENT_STATE=DONE/TRACE

**完成标准**：ANSWER 非空，且内容包含根因描述和处置建议（SOP 引导下的结构化答案）

**前置依赖**：T01, T03

---

## 第三组：图装配

### T11 · ReactAgentStateGraph（新图装配）

**目标**：复制现有 AgentStateGraph 结构，Bean 使用 ReactAgent 节点，名称加 `reactAgent` 前缀

**交付内容**：
- `agent/graph/react/ReactAgentStateGraph.java`（@Configuration）
  - `@Bean("reactAgentKeyStrategyFactory")` → 复用现有 KeyStrategy 工厂（完全相同）
  - `@Bean("reactAgentCompiledGraph")` → 注入 T04–T10 的 7 个 ReactAgent 节点 Bean
  - 图拓扑与现有 AgentStateGraph 完全一致（START→extract→...→END）
  - MemorySaver CompileConfig 同现有配置

**完成标准**：
- Spring 启动后，`agentCompiledGraph` 和 `reactAgentCompiledGraph` 两个 Bean 均可注入
- `reactAgentCompiledGraph.invoke()` 能正常执行，不抛出装配异常

**前置依赖**：T04, T05, T06, T07, T08, T09, T10

---

## 第四组：MCP Client 启用

### T12 · 启用 MCP Client 依赖与配置

**交付内容**：
- `pom.xml`：取消 `spring-ai-starter-mcp-client` 注释
- `McpClientConfig.java`：取消配置注释，激活 Bean（`@ConditionalOnProperty` 已就位）
- `application.properties`（新增配置项，默认关闭，文档注释说明如何开启）：
  ```
  # MCP Client 配置（启用后需配置外部 MCP Server 地址）
  agent.mcp.client.enabled=false
  # spring.ai.mcp.client.connections.filesystem.url=http://localhost:3000/mcp
  ```
- `ToolCallbackAdapter.java`（如尚未处理 MCP ToolCallback 到 AiTool 的反向适配）：
  确认外部 MCP ToolCallback 可直接注入 ReactExecuteNode，无需额外转换

**完成标准**：
- `agent.mcp.client.enabled=false` 时应用正常启动（无外部 MCP 依赖）
- `agent.mcp.client.enabled=true` 且配置了外部地址时，启动日志可见外部工具数量

**前置依赖**：T08

---

## 第五组：Skill 管理控制台

### T13 · SkillAdminController（REST API）

**交付内容**：
- `agent/graph/react/admin/SkillAdminController.java`（@RestController，prefix `/admin/skills`）
  - `GET /admin/skills` → `CompositeSkillRegistry.listAll()` 返回 SkillEntry 列表（含 source 字段）
  - `GET /admin/skills/{name}` → 返回完整 SKILL.md 文本（plain text）
  - `POST /admin/skills` body: `{name, content}` → 校验 frontmatter 合法性 → 写 `./skills/{name}/SKILL.md`
  - `PUT /admin/skills/{name}` body: `{content}` → 仅允许更新 filesystem 层，写文件
  - `DELETE /admin/skills/{name}` → 仅允许删除 filesystem 层，返回 403 if classpath-only
- `agent/graph/react/admin/SkillAdminRequest.java`（record，name + content）
- 校验规则：name 格式（正则 `[a-z0-9-]{1,64}`），frontmatter 含 name/description/domain 三个必填字段

**完成标准**：
- POST 新 Skill → GET 可见（5s 内）
- PUT 更新内容 → GET 返回新内容
- DELETE filesystem Skill → GET 恢复 classpath 版本（若存在）
- DELETE classpath Skill → 返回 403

**前置依赖**：T02

---

### T14 · Skill 管理前端页面

**交付内容**：
- `src/main/resources/static/skills-admin.html`
  - 页面顶部：Skill 列表表格（name / domain / subDomain / source 标签）
  - 列表操作：点击行展开完整 SKILL.md 内容；filesystem Skill 显示"编辑"和"删除"按钮
  - 新增 Skill 区域：name 输入框 + textarea（粘贴完整 SKILL.md）+ 保存按钮
  - 交互：全部走 fetch() 调用 T13 的 REST API，无需页面刷新
  - 样式：复用现有 style.css，保持风格一致

**完成标准**：
- 浏览器访问 `/skills-admin.html`，可见已有 3 个 Skill 列表
- 填写并提交新 Skill，列表刷新后可见新增项（source=filesystem）
- 点击 filesystem Skill 的删除按钮，列表中该条目消失（或回退到 classpath 版本）

**前置依赖**：T13

---

## 第六组：集成测试

### T15 · ReactAgent Graph 端到端集成测试

**交付内容**：
- `GraphReactAgentIntegrationTest.java`（Spring Boot Test，`@SpringBootTest`）
  - 测试 1：完整主路径（支付排查）→ ANSWER 非空，TRACE 含 7 个节点记录
  - 测试 2：追问分支（缺少 payOrderId）→ WAITING=true，CLARIFY_QUESTION 非空
  - 测试 3：重检索分支（mock qualityScore < threshold）→ RETRY_COUNT=1，仍能走到 synthesize
  - 测试 4：并发测试（2 个不同 sessionId 并发请求）→ 各自 ANSWER 独立，不串状态
- 测试配置：使用 Mock LLM（`@MockBean ChatModel`），不依赖真实 API Key

**完成标准**：测试 1–4 全部通过，`mvn test` 绿灯

**前置依赖**：T11

---

## 任务依赖图

```
T01 ──────────────────────────────────────────────────────► T04, T05, T06, T07, T09, T10
T02 ──────────────────────────────────────────────────────► T03, T13
T03 ──────────────────────────────────────────────────────► T08, T10
T04 + T05 + T06 + T07 + T08 + T09 + T10 ─────────────────► T11
T08 ──────────────────────────────────────────────────────► T12
T11 ──────────────────────────────────────────────────────► T15
T13 ──────────────────────────────────────────────────────► T14
```

## 工作量估算

| 任务 | 估时 |
|------|------|
| T01 ReactAgentNode 基类 | 0.5 天 |
| T02 CompositeSkillRegistry | 0.5 天 |
| T03 ReactAgentSkillBridge | 1 天 |
| T04–T07 4 个简单节点 | 各 0.5 天 = 2 天 |
| T08 ReactExecuteNode（核心） | 1.5 天 |
| T09–T10 2 个节点 | 各 0.5 天 = 1 天 |
| T11 ReactAgentStateGraph | 0.5 天 |
| T12 MCP Client 启用 | 0.5 天 |
| T13 SkillAdminController | 1 天 |
| T14 前端页面 | 0.5 天 |
| T15 集成测试 | 1 天 |
| **合计** | **~10 天** |
