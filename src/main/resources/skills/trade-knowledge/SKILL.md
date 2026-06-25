---
name: trade-knowledge
description: 交易规则解释技能，解释交易链路、订单状态流转和业务规则

domain: trade
domain_name: 交易域
domain_description: 处理订单、生单、购物车、履约、退款、取消、逆向售后等问题。
sub_domain: trade_knowledge
sub_domain_name: 交易规则解释

activate_when:
  domain: trade
  sub_domain:
    - trade_knowledge

requires_knowledge: true

tool_flow: []
---

# 交易规则知识库

## 订单状态机

```
购物车 → 下单(CREATED) → 支付(PAID) → 履约(DELIVERING) → 完成(COMPLETED)
                ↓               ↓
           超时关闭         退款(REFUNDING → REFUNDED)
        (CLOSED)
```

## 超时规则

- 下单后 30 分钟未支付：系统自动关闭订单，库存释放
- 支付后 24 小时未履约推进：触发告警，人工介入
- 退款申请后 T+1~T+3 工作日到账（银行卡），T+0（余额）

## 取消规则

| 阶段 | 是否可取消 | 说明 |
|------|-----------|------|
| 已下单未支付 | 是 | 直接关闭，无退款 |
| 已支付未发货 | 是 | 触发退款流程 |
| 已发货 | 否 | 需走售后退货流程 |
| 已完成 | 否 | 只能申请售后 |

## 库存扣减机制

- 下单时预占库存（软锁）
- 支付成功后正式扣减（硬锁）
- 超时未支付释放软锁
- 高并发场景使用分布式锁，超卖需人工补偿
