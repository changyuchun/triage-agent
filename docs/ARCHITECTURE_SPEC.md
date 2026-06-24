# Prism Agent 架构重构 Spec

> 版本：v2.0 | 状态：待实施 | 目标：消除职责爆炸、打通工具依赖、统一 SKILL.md 为单一真相来源

---

## 1. 业界标准对比

| 框架 | 工具注册方式 | 工具依赖建模 | SOP/Prompt 与工具的关系 | 激活条件（shouldActivate）|
|------|-------------|-------------|------------------------|--------------------------|
| **LangChain BaseTool** | `@tool` 装饰器或继承 BaseTool，只有 `name`+`description`+`_run()`，无 category | 无显式 DAG，LLM 自行决定调用顺序；复杂依赖靠 Sequential Chain | SOP 在 System Prompt，与工具完全解耦；工具只返回数据 | 工具本身无激活条件，由 Agent/Router 根据 description 让 LLM 决定 |
| **LangGraph ToolNode** | 工具列表传给 Node，ToolNode 只负责执行；通过 Graph Edge 控制哪些节点能用哪些工具 | 单 Node 内并行（无 DAG）；跨 Node 依赖由 Graph 的边和 State 传递 | SOP 在各 Node 的 system prompt 里；不同 Node 用不同 SOP | 通过 Graph Edge 条件函数控制流转，工具本身无激活逻辑 |
| **LLMCompiler（论文/LangGraph 实现）** | Planner 生成 DAG，每个 Task 含 toolName+args+dependsOn；Executor 按 DAG 拓扑顺序并行调度 | **显式 DAG**：`${step1}` 变量引用前序步骤输出；相互独立的步骤并行执行，速度提升 3.6x | Planner prompt 包含工具列表；SOP/context 可选注入 Planner 引导规划顺序 | 无 shouldActivate；Planner 由 LLM 基于工具描述决定 |
| **Spring AI 2.0 ToolIndex** | 动态工具索引（regex/lucene/vector 三种策略）；运行时搜索相关工具而非全量注入 | 无内置 DAG；依赖 LLM 的 function calling 多轮实现顺序 | system prompt 分离；工具描述是激活的唯一依据 | 无显式 shouldActivate；向量搜索相关工具（34-64% token 减少）|
| **langchain4j（Java）** | `@Tool` 注解扫描；工具是 POJO 方法，无 category/shouldActivate 概念 | 无 DAG；通过 `AiServices` 让 LLM 多轮决定调用顺序 | system prompt 注入在 `@SystemMessage`，与工具定义分离 | 无；让 LLM 基于 description 决定 |

**核心结论**：
- 业界工具接口极其纯粹：**只有描述（schema）+ 执行（run）**，无 category、无 shouldActivate
- 工具路由/激活是 **Router 层（Graph/Agent）** 的职责，不是工具自己的职责
- 工具依赖的业界最佳实践是 **LLMCompiler DAG**：Planner 生成带 `dependsOn` 和 `${stepId}` 变量引用的计划
- SOP 始终在 System Prompt，与工具定义完全解耦

---

## 2. 当前项目问题清单

### P1 AgentSkill 接口职责爆炸

```
interface AgentSkill extends AiTool {
    category()        // 领域归属
    subCategory()     // 子域归属
    shouldActivate()  // 激活条件（工具自己决定自己是否被调用）
    definition()      // 工具 schema（来自 AiTool）
    execute()         // 工具执行（来自 AiTool）
    @Tool method      // Spring AI MCP 暴露（与 execute 重复）
}
```

**问题**：工具负责声明自己在什么条件下被调用，违反 SRP。业界的工具只负责执行，激活条件是 Router 的事。

### P2 三个注册表持有同一对象

```
PaymentStatusSkill 同时被：
  ToolRegistry          （AiTool 接口）
  AgentSkillRegistry    （AgentSkill 接口）
  SkillMcpConfig        （@Tool 注解扫描）
```

而且 `PaymentQueryTool`（旧）和 `PaymentStatusSkill`（新）并存于 ToolRegistry，`toolCode` 不同（`payment.query` vs `payment_query`），TaskExecutionEngine 调用哪个取决于 Planner 输出的 toolCode，存在歧义。

### P3 Metadata 重复、无单一真相来源

- `PaymentStatusSkill.category() = "payment"` 和 `SKILL.md: domain: payment` 重复
- `subCategory() = "pay_status,pay_diagnosis"` 和 SKILL.md frontmatter 的 `sub_domain` 重复
- 两者之间没有代码约束，可以不一致且编译不报错

### P4 dependsOn 字段是摆设

```java
// PlanStep 有 dependsOn 字段
public record PlanStep(String stepId, StepType type, String toolCode,
                       String query, List<String> dependsOn, boolean required, Map<String, Object> args)

// TaskExecutionEngine 完全忽略 dependsOn，无条件并行
List<CompletableFuture<Evidence>> futures = toolSteps.stream()
    .map(step -> CompletableFuture.supplyAsync(() -> executeTool(step, slots)))
    .toList();
```

**问题**：`log_query` 需要 `payment_query` 返回的 `errorCode` 才有意义，但引擎忽略依赖直接并行，`log_query` 拿到的 `errorCode` 是 Slot 里的值（可能为空），而不是工具执行结果里的真实值。

### P5 工具步骤无法引用前序步骤的输出

业界 LLMCompiler 用 `${step1.errorCode}` 语法让后序步骤引用前序输出。当前项目：
- Planner 生成的 `args` 只来自 Slot（静态槽位值）
- `log_query` 的 `keyword` 参数只能填 `slots.errorCode()`，无法填 `paymentQueryResult.errorCode`
- 即使用户没有提供 errorCode，工具执行结果里有 errorCode，也无法传递给 log_query

### P6 SKILL.md SOP 没有注入 Planner

`payment-diagnosis/SKILL.md` 明确写了：
```
排查步骤：
1. 查看 payment_query.riskCode
2. 结合 log_query 确认超时耗时
```
这是工具调用顺序的权威来源，但 `TaskPlanner.llmPlan()` 没有注入这段内容，LLM 无从得知"应该先调 payment_query，再根据结果决定是否调 log_query"。

### P7 动态 Skill 无法支持

新增 Skill 必须：
1. 写一个 @Component Java 类（实现 AgentSkill 的 6 个方法）
2. 写一个 SKILL.md（重复 domain/subDomain metadata）
3. 重启服务

无法从数据库、配置中心、运行时 API 动态注册工具。

---

## 3. 目标架构

### 3.1 架构分层图

```
┌─────────────────────────────────────────────────────────────────┐
│  SKILL.md（单一真相来源）                                        │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │payment-  │  │trade-    │  │log-query │  │任意新增  │       │
│  │diagnosis │  │diagnosis │  │          │  │skill     │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
│  frontmatter: domain/subDomain/toolCodes/activateWhen/toolFlow  │
│  body: SOP（仅供 Synthesizer 用）                               │
└───────────────────────┬─────────────────────────────────────────┘
                        │ SkillRegistry 加载
           ┌────────────▼────────────┐
           │    SkillRegistry        │ ← 唯一注册表
           │  SkillMetadata[]        │   持有 metadata + 关联 AiTool
           │  findByDomain()         │
           │  sopFor()               │
           │  activateRules()        │
           └──────┬──────────┬───────┘
                  │          │
         ┌────────▼──┐  ┌────▼──────────────┐
         │ TaskPlanner│  │ AnswerSynthesizer  │
         │ (规划层)   │  │ (合成层)           │
         │           │  │                   │
         │ 注入:      │  │ 注入:             │
         │ - tool def │  │ - SOP body        │
         │ - toolFlow │  │ (domain+subDomain │
         │   (摘要)   │  │  精确匹配)         │
         └────┬───────┘  └───────────────────┘
              │ ExecutionPlan（DAG）
         ┌────▼──────────────────────┐
         │   TaskExecutionEngine      │
         │   DAG 拓扑排序 + 并行执行  │
         │   ${step1.field} 变量替换  │
         └────┬──────────────────────┘
              │
         ┌────▼──────────────────────┐
         │      ToolRegistry          │ ← 纯执行注册表
         │  Map<toolCode, AiTool>     │   只有 definition() + execute()
         │  call(toolCode, args)      │
         └───────────────────────────┘
                   ▲
         ┌─────────┴──────────────────┐
         │  AiTool 实现（纯执行）      │
         │  PaymentQueryTool          │   无 shouldActivate
         │  TradeQueryTool            │   无 category
         │  LogQueryTool              │   无 @Tool 注解方法
         │  MarketingQueryTool        │
         └────────────────────────────┘
```

### 3.2 拆分原则

| 概念 | 当前归属 | 目标归属 |
|------|---------|---------|
| domain/subDomain | Java 类 + SKILL.md（重复） | 只在 SKILL.md frontmatter |
| shouldActivate | AgentSkill Java 类 | SKILL.md `activate_when` 字段（声明式规则） |
| SOP 内容 | SKILL.md body（正确） | 保持，只注入 Synthesizer |
| 工具调用顺序 | 无（靠 LLM 猜） | SKILL.md `tool_flow` 字段，注入 Planner prompt |
| 工具执行 | AgentSkill.execute() + @Tool（重复） | 只有 AiTool.execute() |
| MCP 暴露 | @Tool 方法（与 execute 重复） | ToolRegistry 适配 Spring AI ToolCallback |

### 3.3 新 AgentSkill 接口（删除，职责归还）

```
AgentSkill 接口 → 删除
AgentSkillRegistry → 合并入 SkillRegistry（新）
DomainSkillLoader → 合并入 SkillRegistry（新）
```

`SkillRegistry` 承接两者职责：
```java
@Component
public class SkillRegistry {
    // 来自 DomainSkillLoader：加载 SKILL.md
    private final Map<String, SkillMetadata> metaByKey;  // key = "domain|subDomain" or "domain"
    // 来自 AgentSkillRegistry：关联 AiTool 执行
    private final Map<String, AiTool> toolByCode;        // key = toolCode

    // 规划层查询
    List<SkillMetadata> findActivatable(RouteResult route, SlotState slots);
    // 合成层查询
    String sopFor(String domain, String subDomain);
    // 执行层
    AiTool toolFor(String toolCode);
}
```

### 3.4 工具依赖 DAG 实现方案

#### SKILL.md tool_flow 格式（新增字段）

```yaml
tool_flow:
  - stepId: payment_query_step
    toolCode: payment_query
    args:
      payOrderId: "${slots.payOrderId}"
      env: "${slots.env}"
    required: true

  - stepId: log_query_step
    toolCode: log_query
    args:
      keyword: "${payment_query_step.errorCode}"   # 引用前序步骤输出字段
      timeRange: "1h"
    dependsOn:
      - payment_query_step
    condition: "${payment_query_step.status} == FAILED"   # 条件执行
    required: false
```

**引用语法**：
- `${slots.payOrderId}` — 从 Slot 取值
- `${stepId.fieldName}` — 从前序步骤的 ToolExecutionResult.data 取值
- `${stepId.status}` — 取前序步骤的成功/失败状态

#### TaskExecutionEngine DAG 执行逻辑

```
1. 构建 DAG：遍历 PlanStep.dependsOn，建立有向边
2. 拓扑排序：Kahn 算法，得到执行层次（同层可并行）
3. 逐层执行：
   for each layer in topologicalLayers:
     resolve args: 替换 ${stepId.field} 占位符（从 stepResults 取值）
     evaluate condition: condition 为 false 则跳过
     parallel execute: 同层所有步骤并行
     store results: stepResults[stepId] = ToolExecutionResult
4. 收集所有 Evidence
```

#### 关键变化：PlanStep.args 支持变量引用

```java
// 当前
Map<String, Object> args  // 静态值

// 目标：args 中的 String 值如果以 "${" 开头，视为变量引用
// TaskExecutionEngine 在执行前做变量替换
// 例：{"keyword": "${payment_query_step.errorCode}"}
//   → {"keyword": "PAY_TIMEOUT"}（取自 payment_query_step 的执行结果）
```

#### 变量替换实现（新增 StepResultContext）

```java
class StepResultContext {
    Map<String, ToolExecutionResult> results = new LinkedHashMap<>();

    Object resolve(String expression) {
        // "${slots.payOrderId}" → slots.payOrderId()
        // "${step1.errorCode}" → results.get("step1").data().get("errorCode")
        if (!expression.startsWith("${")) return expression;
        String path = expression.substring(2, expression.length() - 1);
        String[] parts = path.split("\\.", 2);
        if ("slots".equals(parts[0])) return resolveSlot(parts[1]);
        return results.get(parts[0]).data().get(parts[1]);
    }
}
```

### 3.5 Planner 注入 tool_flow（而非完整 SOP）

**原则**：SOP 的诊断步骤语言 → 结构化 tool_flow → 注入 Planner

Planner prompt 新增字段：
```
推荐工具调用流程（来自领域 SOP，仅供参考，可根据槽位调整）:
step1: payment_query(payOrderId=...) [required]
step2: log_query(keyword=${step1.errorCode}) [dependsOn=step1, condition=step1.status==FAILED]
```

**不注入完整 SOP**，原因：
- SOP 正文 500-1000 token，放进 Planner 大幅增加 Planning 成本
- SOP 的处置建议（"引导用户联系客服"）对规划无意义
- tool_flow 是 SOP 的结构化摘要，Planner 需要的就是这部分

### 3.6 shouldActivate 声明式规则（SKILL.md → SkillRegistry）

```yaml
# SKILL.md frontmatter
activate_when:
  domain: payment
  sub_domain:
    - pay_status
    - pay_diagnosis
  requires:
    - slots.hasObjectId
    - route.handleMode contains "tool"
```

SkillRegistry 解析 `activate_when` 为 `Predicate<ActivationContext>`：

```java
// 不再需要每个 Skill 各自实现 shouldActivate
// SkillRegistry 统一解析 SKILL.md 规则
boolean activatable = metaByKey.values().stream()
    .anyMatch(meta -> meta.activationPredicate().test(new ActivationContext(route, slots)));
```

---

## 4. 修改清单

### 删除

| 文件 | 原因 |
|------|------|
| `skill/AgentSkill.java` | 接口删除，职责分别归 AiTool（执行）和 SkillRegistry（元数据）|
| `skill/AgentSkillRegistry.java` | 合并入新的 SkillRegistry |
| `skill/DomainSkillLoader.java` | 合并入新的 SkillRegistry |
| `skill/skills/PaymentStatusSkill.java` | 废弃，执行逻辑移入 AiTool 实现，元数据移入 SKILL.md |
| `skill/skills/TradeQuerySkill.java` | 同上 |
| `skill/skills/LogQuerySkill.java` | 同上 |
| `mcp/SkillMcpConfig.java` | @Tool 注解注册方式废弃，改用 ToolRegistry 适配 |
| `tool/PaymentQueryTool.java` | 与 PaymentStatusSkill 重复，统一保留一个，CODE 统一为 `payment_query` |

### 新增

| 文件 | 内容 |
|------|------|
| `skill/SkillRegistry.java` | 合并 AgentSkillRegistry + DomainSkillLoader，加载 SKILL.md 并解析 activate_when/tool_flow |
| `skill/SkillMetadata.java` | record：domain/subDomain/toolCodes/sopContent/toolFlow/activationRules |
| `skill/ActivationContext.java` | record：RouteResult + SlotState，传给 activationPredicate |
| `core/StepResultContext.java` | DAG 执行中存储各步骤结果，支持 `${stepId.field}` 变量解析 |
| `mcp/ToolCallbackAdapter.java` | 将 ToolRegistry 中的 AiTool 适配为 Spring AI ToolCallback，替代 @Tool 注解 |

### 修改

| 文件 | 改动内容 |
|------|---------|
| `tool/AiTool.java` | 不变，保持纯粹：`definition()` + `execute()` |
| `tool/ToolRegistry.java` | 不变，只管执行 |
| `tool/PaymentQueryTool.java`（保留，重命名） | 删除旧 `PaymentQueryTool`，内容合入 `tool/impl/PaymentAiTool.java`，CODE 统一为 `payment_query` |
| `core/TaskPlanner.java` | 注入 `SkillRegistry`；`llmPlan()` 从 `SkillRegistry.toolFlowFor()` 获取 tool_flow 注入 Planner prompt |
| `core/TaskExecutionEngine.java` | 实现 DAG 拓扑排序 + `${stepId.field}` 变量替换 + 条件执行 |
| `core/AgentModels.PlanStep` | args 值支持 `${...}` 变量引用字符串（无需改结构，由 Engine 解释） |
| `core/AnswerSynthesizer.java` | 改用 `SkillRegistry.sopFor()` |
| SKILL.md 文件（所有） | 新增 `tool_flow` 字段；`activate_when` 结构化替代 Java 代码 shouldActivate |

### 不变

| 文件 | 原因 |
|------|------|
| `core/AgentModels.java`（其余字段） | RouteResult/SlotState/ExecutionPlan 结构不变 |
| `core/AgentRuntime.java` | 流程不变 |
| `graph/node/*.java` | Graph 节点不变 |
| `rag/` 全部 | RAG 层不受影响 |
| `guardrails/` | 不受影响 |

---

## 5. 新 SKILL.md frontmatter 格式

```yaml
---
name: payment-diagnosis
description: 支付域诊断技能，覆盖渠道超时、风控拦截等核心错误码的排查 SOP

# 领域绑定（单一真相来源，Java 类不再重复声明）
domain: payment
sub_domain: pay_diagnosis        # 单值或列表

# 激活条件（声明式，SkillRegistry 解析为 Predicate，不再写 Java shouldActivate）
activate_when:
  domain: payment
  sub_domain:
    - pay_status
    - pay_diagnosis
  requires:
    - slots.hasObjectId          # 需要有业务对象 ID
    - route.needsTool            # handleMode 包含 tool

# 关联工具（此 Skill 依赖的工具列表）
requires_knowledge: true         # 是否同时需要知识库检索

# 工具调用流程（结构化 DAG，注入 Planner prompt，让 LLM 生成带依赖的执行计划）
tool_flow:
  - stepId: payment_query_step
    toolCode: payment_query
    args:
      payOrderId: "${slots.payOrderId}"
      env: "${slots.env}"
    required: true

  - stepId: log_query_step
    toolCode: log_query
    args:
      keyword: "${payment_query_step.errorCode}"
      timeRange: "1h"
      level: ERROR
    dependsOn:
      - payment_query_step
    condition: "${payment_query_step.status} == FAILED"
    required: false
---

# 支付域诊断 SOP
（以下内容仅注入 AnswerSynthesizer 的 System Prompt，不注入 Planner）

## PAY_TIMEOUT（支付渠道响应超时）
...（原 SOP 内容保持不变）
```

### 与旧格式对比

| 字段 | 旧格式 | 新格式 |
|------|-------|-------|
| 领域绑定 | Java `category()` + SKILL.md `domain:` 各写一遍 | 只在 SKILL.md |
| 子域绑定 | Java `subCategory()` + SKILL.md `sub_domain:` 各写一遍 | 只在 SKILL.md |
| 激活条件 | Java `shouldActivate()` 方法 | SKILL.md `activate_when:` |
| 工具顺序 | 无（靠 LLM 猜，或 rulePlan 硬编码） | SKILL.md `tool_flow:` |
| 工具依赖 | PlanStep.dependsOn 有字段但无语义 | `tool_flow[].dependsOn` + 变量引用 |
| 条件执行 | 无 | `tool_flow[].condition` |
| SOP 内容 | SKILL.md body（正确） | 保持，body 部分不变 |

---

## 6. 重构优先级

| 优先级 | 改动 | 收益 |
|-------|------|------|
| P0 | 删除 `PaymentQueryTool`（旧），统一 CODE 为 `payment_query` | 消除 ToolRegistry 中同功能双 CODE 歧义 |
| P0 | `AgentSkill` 改名/简化，删除 @Tool 方法（与 execute 重复） | 消除 MCP 注册重复 |
| P1 | 合并 `AgentSkillRegistry` + `DomainSkillLoader` → `SkillRegistry` | 消除两个持有同数据的注册表 |
| P1 | SKILL.md 新增 `tool_flow` 字段；TaskPlanner 注入 tool_flow 摘要 | Planner 知道工具调用顺序 |
| P2 | TaskExecutionEngine 实现 DAG 拓扑排序 + `${stepId.field}` 变量替换 | 工具间结果传递成为可能 |
| P2 | SKILL.md `activate_when` 声明式规则替代 Java `shouldActivate` | 新增 Skill 无需写 Java 代码 |
| P3 | `ToolCallbackAdapter` 替代 @Tool 注解，统一 MCP 暴露 | 动态注册支持 |

---

## 参考资料

- [LLMCompiler 论文：An LLM Compiler for Parallel Function Calling](https://arxiv.org/pdf/2312.04511)
- [LangGraph ToolNode 实现](https://deepwiki.com/langchain-ai/langgraph/8.2-toolnode-and-tool-execution)
- [Plan-and-Execute Agents - LangChain Blog](https://blog.langchain.com/planning-agents/)
- [Spring AI 2.0 Tool Calling Architecture](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/)
- [Parallel Tool Calling 依赖排序讨论](https://community.openai.com/t/parallel-tool-calling-where-there-is-an-ordering-dependency/1086995)
