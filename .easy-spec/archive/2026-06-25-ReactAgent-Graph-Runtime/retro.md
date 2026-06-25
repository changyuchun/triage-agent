# 复盘 — ReactAgent Graph Runtime

**归档日期**：2026-06-25  
**实际交付**：T01–T11、T12、T13、T14（T15 集成测试跳过）

---

## 交付 vs 规格差异

**ReactAgentSkillBridge 合并**：规格中 T03 定义了独立的 `ReactAgentSkillBridge` 模块，实际实现中合并进了 `CompositeSkillRegistry`（负责注册表逻辑）+ `ReadSkillTool`（负责按需加载 SKILL.md 内容）。拆分变合并降低了层数，调用链更清晰。

---

## 关键决策评价

**用 `ChatClient + @Tool` 替代 `ReactAgent` 类**：Spring AI Alibaba 1.1.2.3 中无 `ReactAgent` 类，实现中改用 `ChatClient.create(chatModel).prompt().tools(...).call()` 实现等价 ReAct 模式。该决策是本次最核心的架构判断——`ChatClient` 内置工具调用循环，本质上就是 ReAct 的"推理→工具→推理"结构，换名不换质，且 API 更稳定。值得作为后续同类项目的参考基准。

---

## 遗留技术债

- **T15 集成测试跳过**：无端到端测试覆盖，`SpringAiSkillAdvisor.Builder` API 正确性未经真实启动验证。
- **MCP Client 默认关闭**：`spring.ai.mcp.client.enabled=false`，外部 MCP Server 地址需使用时手动配置。
