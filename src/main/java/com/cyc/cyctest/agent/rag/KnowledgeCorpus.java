package com.cyc.cyctest.agent.rag;

import com.cyc.cyctest.agent.rag.KnowledgeModels.KnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeCorpus {
    private final List<KnowledgeChunk> chunks = List.of(
            chunk("pay-001", "payment", "pay_diagnosis", "支付失败 RISK_REJECT",
                    "RISK_REJECT 通常表示支付风控拒绝。排查时先确认支付单状态、风险拒绝码、用户账号风险、金额和渠道返回码。建议结合支付单查询工具和风控策略说明判断。"),
            chunk("pay-002", "payment", "pay_status", "支付单状态流转",
                    "支付单常见状态包括 INIT、PROCESSING、SUCCESS、FAILED、CLOSED。查询具体支付单时需要支付单号和环境。"),
            chunk("pay-003", "payment", "pay_diagnosis", "渠道超时排查",
                    "渠道 TIMEOUT 或 CHANNEL_TIMEOUT 需要查看渠道流水、网关耗时、重试记录和最终回调。工具结果优先于知识库经验。"),
            chunk("pay-004", "payment", "pay_knowledge", "支付链路说明",
                    "支付链路包括收银台建单、支付单创建、渠道请求、渠道回调、支付结果同步、交易履约通知。"),
            chunk("trade-001", "trade", "trade_diagnosis", "订单生单失败排查",
                    "订单生单失败需要确认购物车参数、商品库存、价格校验、营销优惠、交易创建结果和错误码。"),
            chunk("trade-002", "trade", "trade_status", "订单状态说明",
                    "订单状态通常包括 CREATED、PAID、FULFILLED、CANCELLED、REFUNDING、REFUNDED。查询订单需要订单号和环境。"),
            chunk("trade-003", "trade", "trade_knowledge", "交易履约链路",
                    "交易履约链路从支付成功后开始，经过履约单创建、供应商确认、出行或服务履约、售后逆向。"),
            chunk("marketing-001", "marketing", "marketing_diagnosis", "优惠不可用排查",
                    "优惠不可用通常需要检查活动状态、用户资格、商品/订单门槛、预算、限额、渠道和时间窗口。工具结果优先于规则经验。"),
            chunk("marketing-002", "marketing", "marketing_status", "营销限额状态",
                    "营销限额包括用户限额、活动限额、预算限额和并发冻结。查询时需要订单号或 checkoutId、环境和活动信息。"),
            chunk("marketing-003", "marketing", "marketing_knowledge", "优惠叠加规则",
                    "优惠叠加通常受活动优先级、互斥组、券类型、预算和动态规则控制。排查时需要查看命中规则和排除原因。")
    );

    public List<KnowledgeChunk> chunks() {
        return chunks;
    }

    private static KnowledgeChunk chunk(String id, String domain, String subDomain, String title, String content) {
        return new KnowledgeChunk(id, id.substring(0, id.indexOf('-')), domain, subDomain, title, content, 0,
                Map.of("source", "builtin-demo-kb"));
    }
}
