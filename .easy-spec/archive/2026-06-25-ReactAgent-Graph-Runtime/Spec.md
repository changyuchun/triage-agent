# ReactAgent Graph Runtime — 功能规格

## 1. 原始输入

- **需求名称**：ReactAgent Graph Runtime
- **一句话需求**：在现有 AgentStateGraph 基础上增量开发一套新图，节点改用 ReactAgent 实现，并集成 Spring AI Alibaba Skills 渐进式加载机制和 MCP Client/Server 双向工具体系
- **任务类型**：新建规格（现有 AgentStateGraph 保持不变，新建平行实现）

---

## 2. 需求概述

- **需求名称**：ReactAgent Graph Runtime
- **一句话定位**：用 ReactAgent 替代现有图节点的硬编码 Service 调用，实现自主推理 + 工具调用 + Skill 渐进披露的生产级 Agent Graph
- **主要使用方**：后端开发者（开发/调试图节点）；运营/管理员（通过控制台新增/管理 Skill）
- **核心目标**：
  1. 每个图节点封装为持有 ReactAgent 单例的组件，具备自主 Reason-Act 能力
  2. execute/synthesize 节点通过 SkillsAgentHook 实现 Skill 渐进式加载，绑定 groupedTools
  3. 启用 MCP Client（接入外部工具）+ 保留 MCP Server（对外暴露能力）
  4. 提供控制台 REST API + 简单 UI，支持用户新增/编辑/删除 Skill 文件，热重载生效
- **一期范围概述**：复制现有 7 节点图拓扑，节点改用 ReactAgent；新增 ReactAgentSkillBridge、CompositeSkillRegistry、SkillAdminController

---

## 3. 澄清纪要

### 已确认决策

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 是否保留现有 AgentStateGraph | 保留不动，新建 `react/` 子包平行实现 |
| 2 | 图拓扑 | 与现有完全一致（7 节点 + 同样的条件边逻辑） |
| 3 | OverAllState 键值 | 沿用现有 AgentStateKeys，不新增/删除 |
| 4 | ReactAgent 生命周期 | 每节点持有一个 ReactAgent 单例，MemorySaver 按 sessionId 隔离多会话 |
| 5 | Skill 注册表 | Classpath（src/main/resources/skills/）+ FileSystem（./skills/）双层，同名 FileSystem 覆盖 |
| 6 | MCP 方向 | Client + Server 双向：启用 MCP Client 接入外部工具，MCP Server 继续对外暴露 |
| 7 | Skill 格式 | 保留现有 SKILL.md 格式（含 domain/sub_domain/activate_when/tool_flow/SOP body），新增 ReactAgentSkillBridge 做桥接 |
| 8 | 哪些节点挂 SkillsAgentHook | 仅 execute 和 synthesize；其余节点使用 system prompt，不挂 Hook |
| 9 | 节点基类 | 新建 `ReactAgentNode` 抽象基类，统一封装 call() → parseOutput() → 写 state 流程 |
| 10 | 控制台 Skill 管理 | REST API（CRUD）+ 简单 HTML/JS 前端页面，写入 ./skills/ 目录，autoReload 感知 |

### 待确认项

- `[待确认]` MCP Client 具体接入哪些外部 MCP Server（filesystem / brave-search / github）——v1 只打通框架，具体连接按需配置

### 被排除方案

- 替换现有 SKILL.md 格式为 Spring AI Alibaba 标准格式——现有格式更丰富（含 tool_flow/activate_when），改桥接不改格式
- 修改现有 AgentStateGraph 节点——增量开发，老图保留，新图平行存在

### 关键假设

- 当前假设：ReactAgent 输出结构化 JSON，节点 `parseOutput()` 负责解析并写入 OverAllState
- 当前假设：MCP Client v1 先配置 filesystem + brave-search 两个外部工具作为演示，按需扩展

### 术语定义

| 术语 | 定义 |
|------|------|
| ReactAgent | Spring AI Alibaba 提供的 Reason-Act 模式 Agent，支持多轮工具调用 |
| SkillsAgentHook | ReactAgent 的 Hook 扩展，向 System Prompt 注入 Skill 列表，提供 `read_skill` 工具实现渐进式加载 |
| CompositeSkillRegistry | 本项目新增：合并 ClasspathSkillRegistry + FileSystemSkillRegistry，filesystem 覆盖 classpath 同名 skill |
| ReactAgentSkillBridge | 本项目新增：适配现有 SkillRegistry（含 tool_flow/SOP 解析）→ SAA SkillsAgentHook 所需的 SkillRegistry 接口 |
| groupedTools | SkillsAgentHook 的配置项，Map<skillName, List<ToolCallback>>，Skill 加载时才暴露对应工具给 ReactAgent |
| SOP | SKILL.md body 部分，描述领域排查步骤，注入 synthesize 节点 system prompt |
| tool_flow | SKILL.md frontmatter 字段，描述工具调用步骤序列，供 plan 和 execute 节点参考 |

---

## 4. 使用方与场景

### 主要使用方

| 角色 | 使用方式 | 前提 |
|------|---------|------|
| 后端开发者 | 注入 `ReactAgentCompiledGraph` 替换 `AgentCompiledGraph`，观察 ReactAgent 推理过程 | Spring Boot 应用运行 |
| 运营/管理员 | 访问控制台 `/admin/skills`，新增/编辑/删除 SKILL.md | 应用运行，有网络访问 |
| 外部 AI 客户端 | 通过 MCP Server（`/mcp`）调用 Agent 能力 | MCP 兼容客户端 |
| 本 Agent（MCP Client）| 通过 MCP Client 调用外部 MCP Server 工具 | agent.mcp.client.enabled=true |

### 核心场景

**场景 1 — 完整排查对话（主路径）**
用户发起排查 → ReactExtractNode 提取槽位 → ReactRouteNode 路由到 payment 域 → ReactPlanNode 规划工具步骤（参考 payment-diagnosis skill 的 tool_flow）→ ReactExecuteNode 通过 SkillsAgentHook 加载 payment-diagnosis SKILL.md，调用 payment_query + log_query 工具 → ReactSynthesizeNode 加载 SOP 生成最终答案

**场景 2 — 追问补槽**
槽位不全 → ReactExtractNode 判断 needAsk=true → ReactClarifyNode 生成追问 → 等待用户补充 → 下一轮从 extract 重进

**场景 3 — 控制台新增 Skill**
管理员通过 POST /admin/skills 提交新 SKILL.md 内容 → 写入 ./skills/{name}/SKILL.md → CompositeSkillRegistry autoReload 感知 → 下一次 ReactExecuteNode 调用时新 Skill 可发现

---

## 5. 范围

### v1 必做范围

1. **新包 `agent/graph/react/`**：不破坏现有 `agent/graph/` 包
2. **ReactAgentNode 抽象基类**：持有 ReactAgent 单例，模板方法 buildSystemPrompt / buildUserPrompt / parseOutput，action() 统一封装调用链
3. **7 个 ReactAgent 节点实现**：ReactExtractNode / ReactClarifyNode / ReactRouteNode / ReactPlanNode / ReactExecuteNode / ReactReRetrieveNode / ReactSynthesizeNode
4. **ReactAgentSkillBridge**：适配现有 SkillRegistry → SAA SkillsAgentHook 兼容格式；execute 节点用 groupedTools 绑定 AiTool
5. **CompositeSkillRegistry**：合并 ClasspathSkillRegistry（classpath:skills/）+ FileSystemSkillRegistry（./skills/），同名 filesystem 覆盖
6. **ReactAgentStateGraph**：新的 @Configuration，装配 ReactAgent 节点，图拓扑与现有完全一致，Bean 命名加 `reactAgent` 前缀避免冲突
7. **MCP Client 启用**：取消 pom.xml 注释，McpClientConfig 激活，外部 ToolCallback 注入 ReactExecuteNode
8. **SkillAdminController**：REST API（GET /admin/skills，POST /admin/skills，PUT /admin/skills/{name}，DELETE /admin/skills/{name}），写入 ./skills/ 目录
9. **Skill 管理前端页面**：`/admin/skills.html`，列表展示 + Markdown 编辑器（textarea）+ 保存/删除操作

### 非目标

- 修改现有 AgentStateGraph 或任何现有节点
- 修改现有 SkillRegistry（只在其基础上新增 Bridge）
- Skill 版本管理 / 回滚
- 控制台权限认证（v1 不做鉴权）
- 并行节点 / 循环图结构（沿用现有线性 + 条件边拓扑）

### 后续版本

- 控制台 Skill 版本历史 + 回滚
- 控制台权限控制（登录态）
- 动态新增 MCP Server 连接（控制台配置）
- ReactAgent 节点级别的 observability（trace/span 采集）

---

## 6. 核心对象与状态

### 主要实体

```
ReactAgentNode（抽象基类）
├── reactAgent: ReactAgent              // 节点持有的 ReactAgent 单例
├── buildSystemPrompt(): String         // 节点专属系统提示
├── buildUserPrompt(OverAllState): String
├── parseOutput(String, OverAllState): Map<String,Object>
└── action(): AsyncNodeAction           // 供 StateGraph.addNode() 使用

CompositeSkillRegistry（新增）
├── classpathRegistry: ClasspathSkillRegistry
├── filesystemRegistry: FileSystemSkillRegistry
└── merge(): 同名 filesystem 覆盖 classpath

ReactAgentSkillBridge（新增）
├── skillRegistry: SkillRegistry        // 现有自研注册表
├── toSkillsAgentHook(domain, subDomain): SkillsAgentHook
└── toGroupedTools(domain, subDomain): Map<String, List<ToolCallback>>

SkillFile（控制台管理对象）
├── name: String
├── description: String
├── domain: String
├── subDomain: String
├── content: String                     // 完整 SKILL.md 文本
└── source: "classpath" | "filesystem"
```

### 关键状态流转（沿用现有 OverAllState）

```
NEXT_NODE 写入规则（各节点写，条件边读，不变）：
  extract  → "clarify" | "route"
  route    → "clarify" | "plan"
  execute  → "reretrieve" | "synthesize"
```

---

## 7. 功能 / 行为设计

### ReactAgentNode 基类

- **触发**：StateGraph 调用 `action()` 时执行
- **输入**：OverAllState（所有节点共享的状态 Map）
- **输出**：`Map<String, Object>`，写入 OverAllState 对应 key
- **核心规则**：
  1. `reactAgent.call(userPrompt)` 时传入 `threadId = sessionId`，MemorySaver 按 sessionId 隔离状态
  2. ReactAgent 输出必须是结构化 JSON，`parseOutput()` 解析后写入 OverAllState
  3. 解析失败时记录 TRACE 并返回兜底值（不抛出异常，避免图中断）
- **边界**：ReactAgent 超时（默认 30s）时降级返回空结果，写入 AGENT_STATE=ERROR

### ReactExecuteNode（核心节点，最复杂）

- **工具加载顺序**：
  1. 启动时：`ReactAgentSkillBridge.toGroupedTools(domain, subDomain)` → AiTool 转 ToolCallback → 绑定 groupedTools
  2. 每次调用：SkillsAgentHook 向 system prompt 注入当前领域可激活 Skill 列表（name + description）
  3. ReactAgent 判断需要某 Skill 时，调用内置 `read_skill(skillName)` 工具 → 加载完整 SKILL.md 内容（含 tool_flow + SOP）
  4. ReactAgent 按 tool_flow 顺序调用 payment_query / trade_query / log_query 等工具
  5. MCP Client 工具（外部工具）与 AiTool 同时可用，ReactAgent 按需选择
- **输出 State**：EVIDENCE、QUALITY_SCORE、NEXT_NODE（"reretrieve"|"synthesize"）、TRACE

### ReactSynthesizeNode

- **SOP 注入方式**：通过 SkillsAgentHook 加载当前领域 Skill 的 body（SOP），注入 system prompt
- **不调用工具**：纯生成，ReactAgent 无 groupedTools
- **输出 State**：ANSWER、AGENT_STATE=DONE、TRACE

### CompositeSkillRegistry

- **加载优先级**：同名 Skill，FileSystem 覆盖 Classpath
- **热重载**：FileSystemSkillRegistry 配置 `autoReload=true`，Skill 文件变更后自动感知（轮询间隔 5s）
- **范围**：Classpath 加载 `classpath:skills/*/SKILL.md`；FileSystem 加载 `./skills/*/SKILL.md`

### SkillAdminController

| 接口 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 列出所有 Skill | GET | /admin/skills | 返回 classpath + filesystem 所有 Skill 元数据列表，标注 source |
| 获取单个 Skill | GET | /admin/skills/{name} | 返回完整 SKILL.md 文本 |
| 新增 Skill | POST | /admin/skills | body 含 name + content，写入 ./skills/{name}/SKILL.md |
| 更新 Skill | PUT | /admin/skills/{name} | 更新 ./skills/{name}/SKILL.md 内容 |
| 删除 Skill | DELETE | /admin/skills/{name} | 仅可删除 filesystem 层 Skill，classpath Skill 只读 |

---

## 8. 业务规则

### Skill 管理规则

- 新增/更新的 SKILL.md 必须包含合法 YAML frontmatter（name + description + domain 为必填）
- classpath Skill 只读，不允许通过控制台修改或删除
- 同名 Skill 若 classpath 已存在，POST 会创建 filesystem 层覆盖版本（不报错）
- name 格式：小写字母 + 数字 + 连字符，最长 64 字符

### ReactAgent 节点规则

- 每个节点的 ReactAgent 在 Spring 启动时创建（@PostConstruct 或 Bean 注入），不在请求时新建
- MemorySaver 按 `threadId = sessionId` 隔离，不同 sessionId 的 ReactAgent 状态不相互影响
- execute 节点的 groupedTools 在 OverAllState 中确定 domain/subDomain 后**动态**选取（routeNode 写入 ROUTE 后，executeNode 读取并选对应 skill 的工具组）

### 异常处理规则

- ReactAgent 工具调用失败：ReactAgent 内部 retry（最多 2 次），仍失败则 EVIDENCE 标记 tool_error
- JSON 解析失败：parseOutput() 返回兜底 Map（含 TRACE 记录错误，NEXT_NODE 取默认值）
- MCP Client 工具不可用：启动时 warn 日志，不阻断启动；节点调用时跳过不可用工具

---

## 9. 主要流程

### 主路径（ReactAgent 版）

```
1. 用户发起请求 → GraphAgentRuntime 调用 reactAgentCompiledGraph.invoke()
2. ReactExtractNode：
   - ReactAgent prompt = "提取槽位和意图" + userText + 历史
   - 输出 JSON：{slots, clarify, needAsk, nextNode}
   - 写 SLOTS / CLARIFY / NEXT_NODE / CLARIFY_QUESTION
3. 条件边：NEXT_NODE = "clarify" → ReactClarifyNode → END
                           = "route"   → ReactRouteNode
4. ReactRouteNode：
   - ReactAgent prompt = "从候选领域中选择" + userText + slots + domains
   - 输出 JSON：{domainCode, subDomainCode, handleMode, confidence}
   - 写 ROUTE / NEXT_NODE
5. ReactPlanNode：
   - ReactAgent prompt = "规划工具步骤" + route + skills tool_flow 摘要
   - 输出 JSON：{steps: [...]}
   - 写 PLAN
6. ReactExecuteNode：
   - SkillsAgentHook 注入可激活 Skill 列表
   - ReactAgent 调用 read_skill(name) 加载完整 SKILL.md
   - ReactAgent 按 tool_flow 调用 payment_query / log_query / MCP 工具
   - 输出 JSON：{evidence, qualityScore, nextNode}
   - 写 EVIDENCE / QUALITY_SCORE / NEXT_NODE
7. 条件边：质量不足 → ReactReRetrieveNode → execute（最多 1 次重试）
           质量达标 → ReactSynthesizeNode
8. ReactSynthesizeNode：
   - SkillsAgentHook 注入 SOP（SKILL.md body）
   - ReactAgent 生成最终答案
   - 写 ANSWER / AGENT_STATE=DONE
9. END → 返回 ANSWER 给调用方
```

### 异常分支

- ReactAgent 超时 → 写 AGENT_STATE=ERROR，ANSWER="服务暂时不可用，请重试"，直接 END
- Skill 未找到 → execute 节点降级为无 Skill 模式（ReactAgent 依赖通用工具）

---

## 10. 与其他层的边界

### 与现有层

- `AgentStateGraph`（现有）：保持不变，两套图 Bean 名称不冲突（现有 `agentCompiledGraph`，新增 `reactAgentCompiledGraph`）
- `GraphAgentRuntime`：可配置选择使用哪个 CompiledGraph（通过 `@Qualifier` 或配置项切换）
- `SkillRegistry`（现有自研）：只读引用，ReactAgentSkillBridge 包装它，不修改它

### 与外部系统

- MCP Server（已有）：`/mcp` 路径对外，`AgentMcpTools` 继续暴露 chat/searchKnowledge 等工具
- MCP Client（新启用）：接入外部 MCP Server，工具列表以 `List<ToolCallback>` 注入 ReactExecuteNode

---

## 11. 数据与系统约束

- **无数据库变更**：Skill 文件存储在 filesystem，不需要数据库
- **必需配置**：
  - `agent.mcp.client.enabled=true`（启用 MCP Client）
  - `spring.ai.mcp.client.connections.*`（外部 MCP Server 地址，v1 至少配置 1 个）
  - `agent.skills.filesystem.dir=./skills`（FileSystem Skill 目录，默认值）
- **Spring Boot 版本**：3.x（现有约束，不变）
- **Spring AI Alibaba 版本**：1.1.2.3（现有，不升级）
- **ReactAgent 依赖**：`spring-ai-alibaba-graph-core`（已在 pom.xml）

---

## 12. AI 功能

### ReactAgent 节点（核心 AI 能力）

- **用户价值**：每个节点具备自主推理能力，不再依赖硬编码规则，可处理更复杂和多变的输入
- **触发位置**：Graph 执行流中，每个节点处理时触发
- **输入**：OverAllState + 节点专属 prompt
- **输出**：结构化 JSON（解析写入 OverAllState）
- **约束**：输出必须是合法 JSON；使用 `JsonOutputConverter` 或 prompt 强制格式
- **失败处理**：parseOutput() 兜底，不中断图执行

### Skills 渐进式披露（execute / synthesize 节点）

- **用户价值**：减少无关 Skill 对 context window 的占用，按需加载
- **触发位置**：ReactAgent 判断当前任务需要某 Skill 时，调用 `read_skill(name)` 工具
- **输入**：skill name（来自 Skill 列表注入的名称）
- **输出**：完整 SKILL.md 内容（frontmatter + SOP body + tool_flow）
- **约束**：Skill 文件大小建议不超过 8KB，超大 Skill 截断警告
- **失败处理**：read_skill 返回空时，ReactAgent 降级使用通用 prompt

---

## 13. 开放问题

- `[待确认]` MCP Client v1 具体接入哪些外部 MCP Server（影响 McpClientConfig 配置内容）
- `[待确认]` Skill 控制台是否需要简单的身份校验（当前假设 v1 不做鉴权）

---

## 14. 验收标准

1. 启动时 Spring 容器可以同时加载 `agentCompiledGraph`（旧）和 `reactAgentCompiledGraph`（新），不冲突
2. 发送一条支付排查请求，`reactAgentCompiledGraph` 完整执行 7 节点流程，最终返回 ANSWER 非空
3. `ReactExecuteNode` 执行时日志可见 `read_skill(payment-diagnosis)` 被调用，payment_query 工具被执行
4. POST /admin/skills 提交一个新 SKILL.md，5 秒内 GET /admin/skills 可见该 Skill（autoReload 生效）
5. 删除 filesystem 层 Skill 后，classpath 同名 Skill 自动恢复可见（覆盖解除）
6. MCP Client 启用后，ReactExecuteNode 的工具列表中包含外部 MCP 工具名称（日志可验证）
7. 两个不同 sessionId 的并发请求，MemorySaver 不串状态（通过并发测试验证）
