---
name: pay-status
description: 支付状态查询技能，快速获取支付单当前状态和渠道流水

domain: payment
domain_name: 支付域
domain_description: 处理支付单、扣款、支付渠道、风控拒绝、支付状态、渠道流水等问题。
sub_domain: pay_status
sub_domain_name: 支付状态查询

activate_when:
  domain: payment
  sub_domain:
    - pay_status
  requires:
    - slots.hasObjectId

requires_knowledge: false

tool_flow:
  - stepId: payment_query_step
    toolCode: payment_query
    args:
      payOrderId: "${slots.payOrderId}"
      env: "${slots.env}"
    required: true
---

# 支付状态查询指南

用于快速核实支付单当前状态，不做深度根因分析。

## 查询流程

1. 调用 `payment_query`，传入 payOrderId 和 env
2. 读取 `status` 字段：
   - `SUCCESS`：支付成功，告知用户金额和成功时间
   - `PROCESSING`：处理中，告知预计完成时间（通常 5 分钟内）
   - `FAILED`：支付失败，返回 errorCode，建议转 pay_diagnosis 深入排查
   - `REFUNDING` / `REFUNDED`：退款中/已退款，告知退款金额和预计到账
3. 提供渠道（channel）和支付方式（payType）供用户核对

## 状态说明

| status | 含义 | 建议动作 |
|--------|------|---------|
| SUCCESS | 已成功扣款 | 展示支付凭证 |
| PROCESSING | 渠道处理中 | 等待，5 分钟未更新再排查 |
| FAILED | 扣款失败 | 查 errorCode，转诊断排查 |
| REFUNDING | 退款申请中 | 告知 T+1~T+3 到账 |
| REFUNDED | 退款完成 | 展示退款流水 |
