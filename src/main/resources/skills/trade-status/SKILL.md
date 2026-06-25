---
name: trade-status
description: 订单状态查询技能，快速获取订单当前状态和履约进度

domain: trade
domain_name: 交易域
domain_description: 处理订单、生单、购物车、履约、退款、取消、逆向售后等问题。
sub_domain: trade_status
sub_domain_name: 订单状态查询

activate_when:
  domain: trade
  sub_domain:
    - trade_status
  requires:
    - slots.hasObjectId

requires_knowledge: false

tool_flow:
  - trade_query
---

# 订单状态查询指南

## 查询流程

1. 调用 `trade_query`，传入 orderId 和 env
2. 读取 `status` 字段判断订单当前阶段
3. 结合 `fulfillStatus` 判断履约进度

## 订单状态说明

| status | 含义 | 建议动作 |
|--------|------|---------|
| CREATED | 已下单未付款 | 检查支付超时（15~30min） |
| PAID | 已付款待发货 | 确认履约是否已推进 |
| DELIVERING | 配送中 | 提供物流单号 |
| COMPLETED | 已完成 | 展示完结信息 |
| CLOSED | 已关闭 | 确认关闭原因（超时/取消/异常） |
| REFUNDING | 退款中 | 告知退款进度 |

## 履约状态说明

| fulfillStatus | 含义 |
|---------------|------|
| PENDING | 等待履约（> 30min 为异常） |
| PROCESSING | 仓库处理中 |
| DELIVERING | 物流派送中 |
| DELIVERED | 已签收 |
| FAILED | 履约失败，需人工介入 |
