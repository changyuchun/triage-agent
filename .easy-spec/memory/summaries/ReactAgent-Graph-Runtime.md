# 需求摘要 — ReactAgent Graph Runtime

**归档日期**：2026-06-25  
**关联归档**：[→ archive/2026-06-25-ReactAgent-Graph-Runtime/](../archive/2026-06-25-ReactAgent-Graph-Runtime/)

---

## 核心实体

| 实体 | 位置 | 说明 |
|------|------|------|
| `ReactAgentNode` | `agent/graph/react/ReactAgentNode.java` | 所有节点的抽象基类，持有 ChatModel + JsonSupport |
| `CompositeSkillRegistry` | `agent/graph/react/skill/` | 实现 SAA SkillRegistry，桥接 classpath + filesystem 两层，同名 filesystem 优先 |
| `ReadSkillTool` | `agent/graph/react/skill/` | `@Tool` 组件，供 ChatClient 按需加载 SKILL.md 完整内容 |
| `ReactAgentStateGraph` | `agent/graph/react/` | 与现有 AgentStateGraph 平行的新图，Bean 名加 `reactAgent` 前缀 |
| 7 个节点 | `agent/graph/react/node/` | Extract / Clarify / Route / Plan / Execute / ReRetrieve / Synthesize |
| `SkillAdminController` | `agent/graph/react/admin/` | `/admin/skills` CRUD，仅 filesystem 层可写/删 |
| `skills-admin.html` | `src/main/resources/static/` | Skill 管理前端，纯 fetch + 复用 style.css |

---

## 关键决策

**1. 无 ReactAgent 类 → ChatClient + @Tool**  
Spring AI Alibaba 1.1.2.3 中不存在 `ReactAgent` 类。改用 `ChatClient.create(chatModel).prompt().tools(...).call()` 实现等价 ReAct 模式——ChatClient 内置工具调用循环，本质就是"推理→工具→推理"的 ReAct 结构。后续同版本项目可直接沿用此方案，不必再查文档。

**2. ReactAgentSkillBridge 合并**  
规格中 T03 是独立模块，实现中合并进 `CompositeSkillRegistry`（注册表逻辑）+ `ReadSkillTool`（内容加载）。层数从 3 降为 2，调用链更清晰。

**3. 命名冲突处理**  
项目自研 `SkillRegistry` / `SkillMetadata` 与 SAA 同名类冲突，用全限定类名内联解决（Java 不支持 import alias）。

**4. execute/synthesize 节点用 SpringAiSkillAdvisor**  
其余 5 个节点不注入 Advisor，只 extract/clarify/route/plan/reretrieve 做纯 LLM 推理，降低无关 token 消耗。

---

## 已知技术债

- **T15 集成测试跳过**：无端到端覆盖，`SpringAiSkillAdvisor.Builder` API 正确性未经真实启动验证。
- **MCP Client 默认关闭**：需手动配置 `spring.ai.mcp.client.enabled=true` + 外部 Server 地址才能接入。

---

## 经验与教训

- 依赖升级前先确认目标类是否存在（`ReactAgent` 缺失属于版本差异，非配置问题）
- SAA 命名与项目命名大量重叠，新增 SAA 依赖时要做全局冲突扫描
