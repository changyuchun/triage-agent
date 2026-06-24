---
name: log-query
description: 跨领域日志查询技能，用于定位错误根因、获取 traceId 关联链路
domain: "*"

activate_when:
  domain: "*"
  requires:
    - route.isDiagnosis
    - slots.hasErrorCode

requires_knowledge: false
---

# 日志查询使用指南

跨领域辅助能力：当用户提供了错误码、traceId 或支付单号时，补充日志证据帮助定位根因。

## 何时使用日志查询

- 用户提供了 errorCode（如 PAY_TIMEOUT）→ 用 errorCode 作为关键词查日志
- 用户提供了 traceId → 直接用 traceId 查完整链路日志
- 工具查询结果无法解释错误原因 → 用业务 ID（payOrderId/orderId）查关联日志

## 日志查询参数选择

| 场景 | keyword | timeRange | level |
|------|---------|-----------|-------|
| 查具体错误 | errorCode 或 traceId | 1h | ERROR |
| 查超时问题 | 支付单号 + TIMEOUT | 15m | WARN |
| 查全链路 | traceId | 1h | INFO |
| 大促排查 | 渠道名 + 错误码 | 6h | ERROR |

## 结果解读

- `traceId` 相同的日志属于同一次请求链路
- `service` 字段标识哪个微服务打印的日志（payment-gateway/trade-center/risk-engine）
- `ERROR` 级别日志直接指向失败根因
- `WARN` 级别日志关注耗时异常（> 1000ms 需重点关注）

## 注意事项

- 单次查询限制返回最近 100 条，复杂问题需多次查询缩小范围
- 生产环境日志保留 7 天，超时日志无法追溯
- 涉及用户隐私字段（手机号/身份证）的日志需脱敏处理，不在回答中展示
