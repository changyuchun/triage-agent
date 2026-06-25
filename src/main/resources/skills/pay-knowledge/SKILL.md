---
name: pay-knowledge
description: 支付规则解释技能，解释支付链路、状态机、错误码和配置规则

domain: payment
domain_name: 支付域
domain_description: 处理支付单、扣款、支付渠道、风控拒绝、支付状态、渠道流水等问题。
sub_domain: pay_knowledge
sub_domain_name: 支付规则解释

activate_when:
  domain: payment
  sub_domain:
    - pay_knowledge

requires_knowledge: true

tool_flow: []
---

# 支付规则知识库

纯知识解释类技能，无需调用工具，直接基于以下规则给出解答。

## 支付状态机

```
INIT → PENDING（渠道受理）→ PROCESSING（渠道处理中）→ SUCCESS / FAILED
                                                      ↓
                                              REFUNDING → REFUNDED
```

## 常见错误码解释

| 错误码 | 含义 | 用户端表现 |
|--------|------|-----------|
| PAY_TIMEOUT | 渠道响应超时（> 3s） | 支付结果待定，需等待或查询 |
| RISK_REJECT | 风控拦截 | 本次支付被安全系统拦截 |
| INSUFFICIENT_BALANCE | 余额不足 | 账户或绑定卡余额不足 |
| CHANNEL_ERROR | 渠道业务错误 | 渠道侧拒绝，含渠道原始码 |
| DUPLICATE_PAY | 重复支付 | 同一笔订单已有成功记录 |

## 渠道优先级规则

1. 默认按用户绑定渠道顺序路由
2. 主渠道失败后，风控评分 > 0.7 才切备用渠道
3. 大促期间熔断阈值从 50% 降至 30%，更积极切换

## 限额规则

- 单笔上限：默认 5 万元（商户可配置）
- 日累计：默认 20 万元
- 月累计：默认 50 万元
- 超限后需联系客服申请临时提额
