---
name: marketing-status
description: 营销资格查询技能，查询用户或订单的优惠资格、限额占用和权益状态

domain: marketing
domain_name: 营销域
domain_description: 处理优惠券、活动、限额、预算、权益、动态规则、营销资格和优惠叠加等问题。
sub_domain: marketing_status
sub_domain_name: 营销资格查询

activate_when:
  domain: marketing
  sub_domain:
    - marketing_status
  requires:
    - slots.hasObjectId

requires_knowledge: false

tool_flow:
  - stepId: marketing_query_step
    toolCode: marketing_query
    args:
      orderId: "${slots.orderId}"
      couponId: "${slots.couponId}"
      env: "${slots.env}"
    required: true
---

# 营销资格查询指南

## 查询流程

1. 调用 `marketing_query`，传入 orderId 或 couponId
2. 读取以下关键字段：

| 字段 | 含义 |
|------|------|
| status | 券/资格状态（VALID/USED/EXPIRED/FROZEN） |
| quotaUsed | 已用限额（次数或金额） |
| quotaLimit | 限额上限 |
| eligibleAmount | 可享受的优惠金额 |
| ruleMatchResult | 规则匹配结果摘要 |

## 结果解读

- `status=VALID` + `quotaUsed < quotaLimit`：可正常使用优惠
- `status=VALID` + `quotaUsed >= quotaLimit`：配额已满，本期无法使用
- `status=EXPIRED`：优惠券/资格已过期
- `status=FROZEN`：资格被冻结，通常由风控触发，需人工处理
