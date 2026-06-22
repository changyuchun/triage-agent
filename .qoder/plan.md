# 智能答疑 Agent 生产级改造方案

## Context

当前项目是一个基于 Spring Boot 4.1.0 + Spring AI 2.0.0 的智能答疑 Agent，具备状态机驱动的对话流程（EXTRACT→CLARIFY→ROUTE→PLAN→EXECUTE→SYNTHESIZE），但核心基础设施均为 demo 级实现：内存态存储、假向量检索、mock 工具、无 checkpoint。本方案在不修改现有业务逻辑的前提下，通过接口抽象 + `@ConditionalOnProperty` 配置切换，将所有基础设施升级为生产级。

## 基础设施选型

**存储后端：Redis Stack**（一个 Docker 容器同时提供）
- Chat Memory 持久化 + TTL 过期
- VectorStore 向量存储 + 语义检索
- Checkpoint 状态持久化

```bash
# Docker 启动命令
docker run -d --name redis-stack -p 6379:6379 -p 8001:8001 redis/redis-stack:latest
```

**Embedding 模型**：SiliconFlow 的 OpenAI 兼容 Embedding 端点（`BAAI/bge-m3`，1024 维）

---

## Task 1: 记忆存储持久化 + TTL

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/memory/SessionStore.java` | 记忆存储接口（load/save/evict/refreshTtl） |
| `agent/memory/persist/RedisSessionStore.java` | Redis 实现，使用 `StringRedisTemplate` 序列化 ConversationContext |
| `agent/memory/persist/ContextSerializer.java` | SlotState/RouteResult/EvidencePackage ↔ JSON 序列化 |

### 现有文件改动（仅注解）
- `MemoryStore.java` → 加 `@ConditionalOnProperty(name="agent.memory.store", havingValue="inmemory", matchIfMissing=true)` + `implements SessionStore`

### 切换键
`agent.memory.store=inmemory|persistent`（默认 inmemory）

### 关键设计
- `RedisSessionStore` 使用 Redis HASH 存储：`agent:session:{id}` → fields: slots/route/plan/evidence/turns
- 设置 TTL（默认 24h），每次 `load()` 自动续期
- 消息窗口淘汰：保留最近 N 条 turns（可配）

---

## Task 2: 分层记忆 — 长期记忆 + 语义摘要 + 压缩

### 业界参考
- **MemGPT** 三层架构：Core Memory（工作记忆）→ Recall Memory（对话历史）→ Archival Memory（长期向量）
- **LangGraph** checkpoint + memory store：每轮 checkpoint 可恢复，长期记忆写入 VectorStore
- **Cognee** 知识图谱 + 向量混合记忆

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/memory/layered/LongTermMemoryService.java` | 长期记忆接口（consolidate/recall） |
| `agent/memory/layered/SemanticSummarizer.java` | 语义摘要接口（summarize/compress） |
| `agent/memory/layered/VectorStoreLongTermMemory.java` | 基于 VectorStore 的长期记忆实现 |
| `agent/memory/layered/LlmSemanticSummarizer.java` | LLM 驱动的语义摘要（替代字符串拼接） |
| `agent/memory/layered/LayeredMemoryOrchestrator.java` | 5 层记忆编排器 |
| `agent/memory/layered/MemoryConsolidationListener.java` | 事件监听，SYNTHESIZE 完成后异步触发记忆沉淀 |

### 分层设计
```
┌─ SystemMemory ──────── 用户权限、域配置（不变）
├─ WorkingMemory ─────── 当前 slot/route/plan（来自 ConversationContext）
├─ ConversationMemory ── 最近 N 轮对话（滑动窗口，来自 ChatMemory）
├─ SummaryMemory ─────── LLM 语义摘要（SemanticSummarizer 产出）
└─ LongTermMemory ────── 跨会话知识沉淀（VectorStore 语义检索）
```

### 压缩策略
- 滑动窗口：保留最近 20 条对话原文
- 摘要压缩：当未压缩 turns ≥ 6 时，LLM 生成语义摘要替代原文
- 向量沉淀：SYNTHESIZE 完成后，将本次对话的关键结论写入 VectorStore 供未来 recall

---

## Task 3: 真实向量存储 — EmbeddingModel + VectorStore

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/rag/SemanticRetriever.java` | 语义检索接口 |
| `agent/rag/vector/SpringAiVectorRetriever.java` | 基于 Spring AI VectorStore 的真实向量检索 |
| `agent/rag/vector/SpringAiHybridRetriever.java` | BM25 + VectorStore 混合检索 + RRF 融合 |
| `agent/rag/vector/KnowledgeCorpusLoader.java` | 启动时将知识灌入 VectorStore（embedding + 写入） |

### 现有文件改动（仅注解）
- `VectorLikeRetriever.java` → 加 `@ConditionalOnProperty(name="agent.rag.retriever", havingValue="legacy", matchIfMissing=true)` + `implements SemanticRetriever`
- `KnowledgeCorpus.java` → 加同注解（spring-ai 模式下作为初始化数据源）
- `HybridKnowledgeRetriever.java` → 加同注解

### 切换键
`agent.rag.retriever=legacy|spring-ai`（默认 legacy）

### 关键设计
- `SpringAiVectorRetriever` 使用 `VectorStore.similaritySearch(SearchRequest)` 做语义检索
- `SpringAiHybridRetriever` 组合 `Bm25Retriever`（复用）+ `SpringAiVectorRetriever`，用 `ReciprocalRankFusion`（复用）融合
- `KnowledgeCorpusLoader` 在 `@PostConstruct` 中将硬编码知识 + classpath 文档通过 `EmbeddingModel` 编码后批量写入 `VectorStore`
- 知识 Document 的 metadata 包含 `domainCode`/`subDomainCode` 用于过滤检索

---

## Task 4: MCP Tool — Spring AI MCP Client 集成

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/tool/mcp/McpToolBridge.java` | MCP ToolCallback → AiTool 适配器 |
| `agent/tool/mcp/McpAiToolAdapter.java` | 单个 MCP 工具包装为 AiTool |
| `agent/tool/mcp/McpToolRegistryEnhancer.java` | 将 MCP 工具注册到 ToolRegistry |

### 现有文件改动（仅注解）
- `PaymentQueryTool.java` → 加 `@ConditionalOnProperty(name="agent.tool.mcp.enabled", havingValue="false", matchIfMissing=true)`
- `TradeQueryTool.java` → 加同注解
- `MarketingQueryTool.java` → 加同注解

### 切换键
`agent.tool.mcp.enabled=false|true`（默认 false）

### 关键设计
- `McpToolBridge` 注入 `SyncMcpToolCallbackProvider`，遍历所有 MCP ToolCallback
- `McpAiToolAdapter` 将 MCP 的 `ToolCallback.getToolDefinition()` 映射为项目的 `ToolModels.ToolDefinition`
- `McpToolRegistryEnhancer` 使用 `BeanPostProcessor` 或 `@EventListener(ApplicationReadyEvent)` 将适配后的 AiTool 追加到 ToolRegistry
- MCP 模式下 mock 工具自动停用，通过 `@ConditionalOnProperty` 互斥

---

## Task 5: Checkpoint — 状态机断点恢复

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/core/checkpoint/CheckpointStore.java` | Checkpoint 存储接口（save/load/delete/exists） |
| `agent/core/checkpoint/RedisCheckpointStore.java` | Redis 实现，JSON 序列化 AgentRunContext |
| `agent/core/checkpoint/InMemoryCheckpointStore.java` | 内存实现（开发用） |
| `agent/core/checkpoint/CheckpointableRuntime.java` | 装饰器，包装 AgentRuntime 增加 checkpoint 能力 |

### 切换键
`agent.runtime.checkpoint.enabled=false|true`（默认 false）
`agent.runtime.checkpoint.backend=redis|inmemory`（默认 redis）

### 关键设计
- `CheckpointableRuntime` 装饰 `AgentRuntime`：
  - `run()` 入口：检查 `checkpointStore.exists(sessionId)` → 有则恢复 AgentRunContext 继续执行
  - 每次状态转换后：`checkpointStore.save(ctx)` 写入
  - `WAITING_USER_INPUT` 时保留 checkpoint，下一轮恢复
  - `DONE`/`FAILED` 时 `checkpointStore.delete(sessionId)` 清除
- Redis key：`agent:checkpoint:{sessionId}`，TTL 1h
- AgentRunContext 是 record，所有字段可序列化

---

## Task 6: Spring AI Advisor — ChatClient + Advisor 链

### 新建文件
| 文件 | 职责 |
|------|------|
| `agent/advisor/AgentLoggingAdvisor.java` | 日志/可观测 Advisor（记录 token、耗时） |
| `agent/advisor/AgentContextInjectionAdvisor.java` | 上下文注入 Advisor（slot/route/evidence → system prompt） |
| `agent/advisor/AgentRagAdvisor.java` | RAG 增强 Advisor（调用前自动检索知识） |
| `agent/llm/ChatClientLlmService.java` | ChatClient 封装，集成 Advisor 链 |
| `agent/core/AdvisorAgentRuntime.java` | Advisor 模式 Runtime（使用 ChatClient 替代裸调 LlmClient） |

### 现有文件改动（仅注解）
- `AgentRuntime.java` → 加 `@ConditionalOnProperty(name="agent.advisor.enabled", havingValue="false", matchIfMissing=true)`

### 切换键
`agent.advisor.enabled=false|true`（默认 false）

### Advisor 链顺序
```
请求 → AgentLoggingAdvisor(order=10)
     → MessageChatMemoryAdvisor(内置, order=100)
     → AgentContextInjectionAdvisor(order=200)
     → AgentRagAdvisor(order=300)
     → LLM Call
     → AgentLoggingAdvisor 记录响应
```

### 关键设计
- `ChatClientLlmService` 使用 `ChatClient.builder(chatModel).defaultAdvisors(...)` 构建
- 每次调用传入 `ChatMemory.CONVERSATION_ID` 参数
- `AdvisorAgentRuntime` 复制 AgentRuntime 状态机结构，但所有 LLM 调用走 ChatClient
- 内置 Advisor 复用：`MessageChatMemoryAdvisor`（记忆）、`SimpleLoggerAdvisor`（日志）

---

## pom.xml 依赖新增

```xml
<!-- Task 1: 记忆持久化 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-redis</artifactId>
</dependency>

<!-- Task 3: 向量存储 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-redis</artifactId>
</dependency>

<!-- Task 4: MCP Client -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

---

## application.properties 新增

```properties
# ===== 改造开关 =====
agent.memory.store=inmemory
agent.memory.ttl-seconds=86400
agent.memory.max-messages=50

agent.memory.layered.enabled=false
agent.memory.summary.threshold=6

agent.rag.retriever=legacy

agent.tool.mcp.enabled=false

agent.runtime.checkpoint.enabled=false
agent.runtime.checkpoint.backend=redis

agent.advisor.enabled=false

# ===== Spring AI Redis (Task 1 + 3 共用) =====
spring.data.redis.host=localhost
spring.data.redis.port=6379

# ===== Spring AI VectorStore (Task 3) =====
spring.ai.vectorstore.redis.initialize-schema=true
spring.ai.vectorstore.redis.index-name=agent-knowledge-idx
spring.ai.vectorstore.redis.prefix=embedding:

# ===== Spring AI Embedding (Task 3) =====
spring.ai.openai.embedding.base-url=https://api.siliconflow.cn/v1
spring.ai.openai.embedding.api-key=${SILICONFLOW_API_KEY:sk-klevkrdkbgattcxaoidmipmphcwlkxnlpckpbwihfdwmzgvu}
spring.ai.openai.embedding.model=BAAI/bge-m3

# ===== Spring AI MCP (Task 4) =====
spring.ai.mcp.client.enabled=false
spring.ai.mcp.client.type=SYNC
spring.ai.mcp.client.request-timeout=30s
```

---

## @ConditionalOnProperty 切换总表

| 现有类 | 切换键 | 默认值 |
|-------|--------|--------|
| `MemoryStore` | `agent.memory.store` | inmemory (matchIfMissing=true) |
| `VectorLikeRetriever` | `agent.rag.retriever` | legacy (matchIfMissing=true) |
| `KnowledgeCorpus` | `agent.rag.retriever` | legacy (matchIfMissing=true) |
| `HybridKnowledgeRetriever` | `agent.rag.retriever` | legacy (matchIfMissing=true) |
| `PaymentQueryTool` | `agent.tool.mcp.enabled` | mock (havingValue=false, matchIfMissing=true) |
| `TradeQueryTool` | `agent.tool.mcp.enabled` | mock (havingValue=false, matchIfMissing=true) |
| `MarketingQueryTool` | `agent.tool.mcp.enabled` | mock (havingValue=false, matchIfMissing=true) |
| `AgentRuntime` | `agent.advisor.enabled` | legacy (havingValue=false, matchIfMissing=true) |
| `SiliconFlowLlmClient` | `agent.llm.provider` | siliconflow (已有) |
| `SpringAiLlmClient` | `agent.llm.provider` | spring-ai (已有) |

---

## 新建文件汇总（共 22 个 .java + 1 个配置文件）

| # | 路径 | Task |
|---|------|------|
| 1 | `agent/memory/SessionStore.java` | T1 |
| 2 | `agent/memory/persist/RedisSessionStore.java` | T1 |
| 3 | `agent/memory/persist/ContextSerializer.java` | T1 |
| 4 | `agent/memory/layered/LongTermMemoryService.java` | T2 |
| 5 | `agent/memory/layered/SemanticSummarizer.java` | T2 |
| 6 | `agent/memory/layered/VectorStoreLongTermMemory.java` | T2 |
| 7 | `agent/memory/layered/LlmSemanticSummarizer.java` | T2 |
| 8 | `agent/memory/layered/LayeredMemoryOrchestrator.java` | T2 |
| 9 | `agent/memory/layered/MemoryConsolidationListener.java` | T2 |
| 10 | `agent/rag/SemanticRetriever.java` | T3 |
| 11 | `agent/rag/vector/SpringAiVectorRetriever.java` | T3 |
| 12 | `agent/rag/vector/SpringAiHybridRetriever.java` | T3 |
| 13 | `agent/rag/vector/KnowledgeCorpusLoader.java` | T3 |
| 14 | `agent/tool/mcp/McpToolBridge.java` | T4 |
| 15 | `agent/tool/mcp/McpAiToolAdapter.java` | T4 |
| 16 | `agent/tool/mcp/McpToolRegistryEnhancer.java` | T4 |
| 17 | `agent/core/checkpoint/CheckpointStore.java` | T5 |
| 18 | `agent/core/checkpoint/RedisCheckpointStore.java` | T5 |
| 19 | `agent/core/checkpoint/InMemoryCheckpointStore.java` | T5 |
| 20 | `agent/core/checkpoint/CheckpointableRuntime.java` | T5 |
| 21 | `agent/advisor/AgentLoggingAdvisor.java` | T6 |
| 22 | `agent/advisor/AgentContextInjectionAdvisor.java` | T6 |
| 23 | `agent/advisor/AgentRagAdvisor.java` | T6 |
| 24 | `agent/llm/ChatClientLlmService.java` | T6 |
| 25 | `agent/core/AdvisorAgentRuntime.java` | T6 |
| 26 | `resources/mcp-servers.json` | T4 |

## 实施顺序

```
T1 (记忆持久化) → T3 (向量存储) → T2 (分层记忆，依赖 T1+T3)
                                       ↓
T4 (MCP Tool) → T5 (Checkpoint) → T6 (Advisor 链)
```

## 验证方式

1. `docker run -d --name redis-stack -p 6379:6379 -p 8001:8001 redis/redis-stack:latest`
2. `./mvnw compile` 编译通过
3. 默认配置启动：`./mvnw spring-boot:run`（所有开关默认，走旧实现）
4. 逐个切换开关验证新实现
5. Redis 数据验证：`redis-cli` 查看 `agent:session:*` / `embedding:*` key
