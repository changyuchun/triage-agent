---
name: payment-diagnosis
description: 支付域诊断技能，覆盖渠道超时、风控拦截、余额不足、重复支付等核心错误码的排查 SOP

domain: payment
domain_name: 支付域
domain_description: 处理支付单、扣款、支付渠道、风控拒绝、支付状态、渠道流水等问题。
sub_domain: pay_diagnosis
sub_domain_name: 支付失败排查

activate_when:
  domain: payment
  sub_domain:
    - pay_status
    - pay_diagnosis
  requires:
    - slots.hasObjectId
    - route.needsTool

requires_knowledge: true

tool_flow:
  - payment_query
  - log_query
---

# 支付域诊断 SOP

你是支付领域的专家诊断 Agent。当用户反馈支付问题时，严格按照以下 SOP 排查，结合工具返回的证据给出根因和处置建议。

## PAY_TIMEOUT（支付渠道响应超时）

**根因**：支付渠道响应时间超过 3000ms，触发熔断保护。常见于大促流量洪峰或渠道侧故障。

**排查步骤**：
1. 查看 `payment_query.retryCount`：≥ 3 表示已多次重试，渠道持续不可用
2. 查看 `payment_query.channel`：确认是哪条渠道（alipay/wxpay/unionpay）
3. 结合 `log_query` 确认超时耗时和 traceId，判断是渠道单点还是全量异常
4. 若渠道全量告警：联系渠道值班，确认是否已切备用渠道
5. 若仅个别订单超时：可引导用户重新发起支付

**处置建议**：
- 渠道持续告警 → 申请切换备用渠道，上报 SRE
- 偶发超时 → 引导用户重试，监控后续错误率

---

## RISK_REJECT（风控拦截）

**根因**：支付请求命中风控规则，设备、账号或行为被判定为高风险。

**排查步骤**：
1. 查看 `payment_query.riskCode`：
   - `DEVICE_RISK`：设备指纹高风险，联系风控团队提供 deviceId 人工审核
   - `USER_LIMIT`：用户当日/月累计限额用尽，需等限额重置或申请提额
   - `BLACK_LIST`：账号在黑名单，走风控申诉流程
   - `VELOCITY`：短时间内支付频次异常，触发频控
2. 确认用户是否近期有异常登录或换设备操作

**处置建议**：
- `DEVICE_RISK`/`BLACK_LIST` → 引导用户联系客服，走风控申诉，勿直接让其重试
- `USER_LIMIT` → 告知限额政策，引导次日重试或申请临时提额
- `VELOCITY` → 冷却期（通常 1h）后重试

---

## CHANNEL_ERROR（渠道返回业务错误）

**根因**：渠道侧业务拒绝，非超时，常见于账户状态异常或参数问题。

**排查步骤**：
1. 查看 `payment_query.errorMsg` 中的渠道原始错误码
2. alipay 常见：`ACQ.SYSTEM_ERROR`（渠道内部异常，可重试）、`ACQ.TRADE_HAS_SUCCESS`（重复支付）
3. wxpay 常见：`NOAUTH`（商户未开通权限）、`BANKCARD_DEBIT_OVERDRAFT`（储蓄卡限额）

**处置建议**：
- 渠道内部异常 → 等待 5 分钟重试，连续失败上报渠道运营
- 商户权限问题 → 联系渠道商务开通对应能力

---

## INSUFFICIENT_BALANCE（余额不足）

**根因**：用户账户余额或绑定银行卡余额不足。

**处置建议**：
- 引导用户充值或更换支付方式，提示可用余额查看路径

---

## DUPLICATE_PAY（重复支付）

**根因**：同一订单被多次发起支付，通常因前端重复提交或网络重试导致。

**排查步骤**：
1. 查看 `payment_query.channelOrderId` 是否已存在成功记录
2. 核查交易侧订单是否已完成履约

**处置建议**：
- 若已支付成功：安抚用户，展示支付凭证，告知多余款项会自动退回
- 若两笔均失败：按正常失败流程排查
