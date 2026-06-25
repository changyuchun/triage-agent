# Skill 驱动路由 — 功能规格

## 1. 原始输入

- **需求名称**：Skill 驱动路由
- **一句话需求**：让 DomainRegistry 从 SKILL.md 动态聚合域信息，消除 enabledDomains 硬编码，使 SKILL.md 成为路由和执行的单一信息源
- **任务类型**：增量改造（现有接口不变，改内部实现）

---

## 2. 需求概述

DomainRegistry 当前硬编码 3 个域 9 个子域，与 SKILL.md 完全割裂：新增 SKILL.md 不会让路由器感知新领域。本需求将 DomainRegistry 改为从 SkillRegistry 动态聚合，同时补全缺失的 7 个子域 SKILL.md，并为 SKILL.md frontmatter 补充路由所需的中文名称和描述字段。

**主要使用方**：DomainRouter（经典版）、RouteNode（Graph 版）、ReactRouteNode（ReactAgent 版）

---

## 3. 澄清纪要

### 已确认决策
- DomainRegistry 接口（`enabledDomains()`、`contains()`、`findDomain()`）保持不变，调用方零改动
- 新增 SKILL.md：pay_status、pay_knowledge、trade_status、trade_knowledge、marketing_status、marketing_diagnosis、marketing_knowledge（7 个）
- 加载失败时 `enabledDomains()` 返回空列表，路由降级走 heuristicRoute，不阻止启动

### 被排除方案
- 直接删除 DomainRegistry：调用方改动面过大，不采用
- DomainRouter / RouteNode / ReactRouteNode 直接依赖 SkillRegistry：不改调用方

---

## 4. 核心问题

| # | 文件 | 问题 |
|---|------|------|
| P1 | DomainRegistry.java | enabledDomains() 硬编码，与 SKILL.md 割裂 |
| P2 | DomainRouter.java | 路由候选列表来自硬编码，非 SKILL.md |
| P3 | ReactRouteNode.java | buildSystemPrompt() 从硬编码域列表构建候选 |
| P4 | SKILL.md frontmatter | 缺 domain_name、sub_domain_name、domain_description |
| P5 | skills/ 目录 | 7 个子域无 SKILL.md，路由器感知不到 |

---

## 5. 改动范围（v1）

### 必做
1. **SkillMetadata** 增加 3 个字段：`domainName`、`subDomainName`、`domainDescription`
2. **SkillRegistry.parseAndRegister()** 解析新字段
3. **DomainRegistry** 改为注入 SkillRegistry，`enabledDomains()` 动态聚合；`contains()`/`findDomain()` 从聚合结果派生
4. **现有 SKILL.md**（payment-diagnosis、trade-diagnosis）补充 3 个新字段
5. **新增 7 个 SKILL.md**（见下表），包含 frontmatter + SOP + tool_flow

| 新增文件 | domain | sub_domain |
|---------|--------|-----------|
| pay_status | payment | pay_status |
| pay_knowledge | payment | pay_knowledge |
| trade_status | trade | trade_status |
| trade_knowledge | trade | trade_knowledge |
| marketing_diagnosis | marketing | marketing_diagnosis |
| marketing_status | marketing | marketing_status |
| marketing_knowledge | marketing | marketing_knowledge |

### 非目标
- 不改 DomainRouter / RouteNode / ReactRouteNode 调用逻辑
- 不引入文件系统动态加载（CompositeSkillRegistry 已有，但本次路由聚合只用 classpath 层）

---

## 6. 业务规则

- **聚合规则**：crossDomain（domain="*"）的 SKILL.md 不参与 DomainCandidate 构建
- **子域过滤**：subDomain 为空的 SKILL.md 不生成 SubDomainCandidate
- **降级规则**：SkillRegistry 加载后 allSkills() 为空时，enabledDomains() 返回空列表，heuristicRoute 按关键词路由

---

## 7. 验收标准

1. 删除 DomainRegistry 中的 `private final List<DomainCandidate> domains = List.of(...)` 硬编码，`enabledDomains()` 返回从 SKILL.md 聚合的结果
2. `enabledDomains()` 返回 3 个域（payment/trade/marketing），每域 3 个子域，共 9 个子域
3. DomainRouter / RouteNode / ReactRouteNode 不需要任何代码改动
4. 新增 SKILL.md 在 SkillRegistry 日志中可见：`加载 X 个 SKILL.md`（X 从 3 增到 10）
5. 编译通过，`./mvnw compile` 无错误
