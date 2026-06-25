---
name: marketing-knowledge
description: 营销规则解释技能，解释优惠叠加、限额、活动预算、动态规则配置

domain: marketing
domain_name: 营销域
domain_description: 处理优惠券、活动、限额、预算、权益、动态规则、营销资格和优惠叠加等问题。
sub_domain: marketing_knowledge
sub_domain_name: 营销规则解释

activate_when:
  domain: marketing
  sub_domain:
    - marketing_knowledge

requires_knowledge: true

tool_flow: []
---

# 营销规则知识库

## 优惠叠加规则

默认规则（可由活动配置覆盖）：
- 平台券 + 店铺券：可叠加
- 平台券 + 平台券：不可叠加（取最优）
- 满减 + 折扣：先折扣后满减（顺序固定）
- 积分抵扣：可与优惠券叠加，最后计算

## 限额类型

| 类型 | 说明 |
|------|------|
| 个人参与次数限额 | 每用户每活动可参与 N 次 |
| 个人金额限额 | 每用户每期最多享受 X 元优惠 |
| 活动总量限额 | 全体用户共享的发放总量 |
| 渠道限额 | 特定渠道（APP/小程序）专属限额 |

## 活动预算机制

- 预算由活动运营配置，实时消耗
- 消耗超过 80%：触发预警
- 消耗达 100%：活动自动暂停（PAUSED）
- 风控检测到异常羊毛：活动临时冻结（FROZEN），需人工审核恢复

## 动态规则配置

规则引擎支持的条件类型：
- 用户属性：等级、注册时长、地区、设备类型
- 商品属性：类目、品牌、价格区间
- 订单属性：支付方式、订单金额、是否首单
- 时间条件：活动有效期、特定时段（秒杀）
