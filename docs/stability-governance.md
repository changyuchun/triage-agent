# Agent 稳定性治理方案

> 适用场景：生产级 Agent 系统（LLM 规划 + 工具调用 + 知识检索）。本文档沉淀自 cyctest 项目的实际架构演进，可直接用于面试表述。

---

## 一、整体思路

Agent 的不稳定来自三个来源：**LLM 不确定性**、**工具调用瞬时失败**、**执行链路超时**。治理策略对应三条线：

```
LLM 不确定性  →  确定性兜底（规则降级 + 输出校验 + Guardrails）
工具失败      →  重试 + 熔断 + 降级答案
超时          →  单工具 Timeout + 整体请求 Deadline
```

---

## 二、LLM 不确定性治理

### 2.1 规划阶段的双轨策略

| 路径 | 触发条件 | 说明 |
|------|---------|------|
| LLM Plan | LLM 可用 | 注入完整 SOP + 工具定义，LLM 推断 DAG |
| Rule Plan | LLM 不可用 / LLM 输出非法 | 按工具列表顺序执行，无跨步骤依赖，纯兜底 |

Rule Plan 是最后的安全网，保证 Agent 在 LLM 故障时仍能返回有限度的有效答案（至少调了工具或检索了知识）。

### 2.2 Plan 输出校验（validateOrFallback）

LLM Plan 产出后立即做两步校验：
1. **toolCode 白名单**：SKILL.md 声明的 tools 列表是唯一合法工具集，hallucinate 的 toolCode 直接丢弃
2. **知识检索补全**：handleMode 要求 knowledge 但 LLM 漏掉了，自动补插 knowledge_guard 步骤

校验后如果步骤为空，自动回退到 Rule Plan。

### 2.3 Guardrails

- **输入侧**：过滤注入攻击、超长输入、敏感词
- **输出侧**：格式强制（五段结构 + 600 字以内）+ 事实引用标注 [ref:evidenceId]，LLM 不能编造没有 evidence 支撑的事实

---

## 三、工具调用稳定性

### 3.1 重试策略

```java
// TOOL_EXCEPTION（网络、超时、5xx）：指数退避重试，最多 maxToolRetries 次
// TOOL_NOT_FOUND / TOOL_PARAM_MISSING：配置错误，不重试（重试无意义）
for (int attempt = 0; attempt <= maxRetries; attempt++) {
    if (attempt > 0) sleep(200ms * attempt);  // 200ms, 400ms
    result = callTool(toolCode, args);
    if (success || configError) break;
}
```

**关键原则**：区分**瞬时错误**（可重试）和**配置错误**（不可重试），避免无效重试放大延迟。

### 3.2 单工具超时保护

```java
CompletableFuture.supplyAsync(() -> callToolWithRetry(...))
    .orTimeout(toolTimeoutMs, TimeUnit.MILLISECONDS)
    .exceptionally(ex -> ToolExecutionResult.failed("TOOL_TIMEOUT", ...))
```

- 默认超时：5000ms（可通过 `agent.runtime.tool-timeout-ms` 配置）
- 超时不抛异常、不阻断 DAG，产出 `tool_error` 类型的 Evidence，Synthesizer 在答案里标注"部分工具超时"

**为什么不用 allOf 整体 join 超时**：单工具超时是独立的，一个工具慢不应该影响其他并行工具；整体 join 超时会把所有未完成的工具都掐掉。

### 3.3 熔断（待实现）

熔断是重试的上层：当某个工具在滑动窗口内失败率超过阈值（如 50%），短路后续调用，直接返回降级结果，不再等 timeout。

```
适合实现：Resilience4j CircuitBreaker per toolCode
状态机：CLOSED → OPEN（失败率超阈值）→ HALF_OPEN（探测）→ CLOSED（恢复）
```

### 3.4 工具失败对最终答案的影响

```
tool_error Evidence → AnswerSynthesizer 识别 → 答案标注"部分数据不可用"
所有工具都失败 → qualityScore 极低 → 走 ReRetrieve → 纯知识库回答
```

降级链路：工具答案 → 知识库答案 → 兜底模板答案，三层保底。

---

## 四、执行链路稳定性

### 4.1 DAG 执行的稳定性设计

```
拓扑排序（Kahn 算法）+ 层内并行（CompletableFuture.allOf）
条件步骤（condition）不满足时跳过，不影响其他步骤
变量引用（${stepId.field}）前置步骤缺失时返回空串兜底，不抛异常
```

### 4.2 证据质量评分 + ReRetrieve

执行完不直接合成，先打质量分：

```
qualityScore = maxToolScore + 0.35（有 tool Evidence）+ 0.20（有 knowledge Evidence）
```

低质量（< minEvidenceScore）且未重试过 → 走 ReRetrieve 节点，改写 Query 重新检索知识库，再合成。

### 4.3 整体请求 Deadline（待实现）

Agent 请求链路：Extract → Route → Plan → Execute → Synthesize，每个节点有 LLM 调用。整体需要一个 Deadline（如 30s），超时直接返回已收集到的 Evidence 合成的降级答案，不等后续节点。

---

## 五、可观测性

| 维度 | 内容 |
|------|------|
| Trace | 每个节点的状态转移 + 耗时（state.trace 链路） |
| Evidence | 每个工具/知识步骤的结果类型和质量分 |
| 工具失败率 | tool_error 占总 Evidence 的比例，超阈值告警 |
| LLM fallback 率 | llmPlan 失败回退到 rulePlan 的频率，高频说明 LLM 服务或 Prompt 有问题 |
| 质量分分布 | qualityScore 直方图，P50/P95 低于阈值说明工具或知识库质量下降 |

---

## 六、面试表述要点

**Q: 工具调用失败怎么处理？**

> 分三层：首先区分瞬时错误和配置错误，瞬时错误用指数退避重试（200ms, 400ms），配置错误不重试直接返回；其次每个工具调用有独立 timeout（5s），超时不阻断 DAG，产出 tool_error Evidence；最后 qualityScore 评分，全失败时走 ReRetrieve 重试知识库，保证最终有答案。

**Q: Agent 的稳定性怎么保证？**

> 三条线：第一，LLM 不确定性用双轨规划（LLM Plan + Rule Plan 降级）和输出校验（toolCode 白名单 + 格式约束）兜底；第二，工具调用用重试 + timeout + 熔断保护，失败降级到知识库答案；第三，整体链路用证据质量评分 + ReRetrieve 机制，低质量不直接给用户。可观测性上记录每个节点的 trace 和 Evidence，能快速定位是 LLM 问题、工具问题还是知识库问题。

**Q: 为什么不直接 allOf 整体超时？**

> 同层工具是并行的，一个工具慢不应该让其他工具白跑。单工具 orTimeout 让每个工具独立计时，超时的产出 tool_error Evidence，其他正常完成的工具结果仍然有效，Synthesizer 能基于部分证据给出有限答案。整体 allOf 超时会把所有未完成的都掐掉，损失更多信息。
