package com.cyc.cyctest.agent.domain;

import com.cyc.cyctest.agent.domain.DomainModels.DomainCandidate;
import com.cyc.cyctest.agent.domain.DomainModels.SubDomainCandidate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DomainRegistry {
    private final List<DomainCandidate> domains = List.of(
            new DomainCandidate(
                    "payment",
                    "支付域",
                    "处理支付单、扣款、支付渠道、风控拒绝、支付状态、渠道流水等问题。",
                    List.of(
                            new SubDomainCandidate("pay_status", "支付状态查询", "查询支付单、扣款状态、渠道状态。"),
                            new SubDomainCandidate("pay_diagnosis", "支付失败排查", "排查支付失败、风控拒绝、渠道失败、超时等问题。"),
                            new SubDomainCandidate("pay_knowledge", "支付规则解释", "解释支付链路、状态机、错误码和配置规则。")
                    )
            ),
            new DomainCandidate(
                    "trade",
                    "交易域",
                    "处理订单、生单、购物车、履约、退款、取消、逆向售后等问题。",
                    List.of(
                            new SubDomainCandidate("trade_status", "订单状态查询", "查询订单、生单、履约状态。"),
                            new SubDomainCandidate("trade_diagnosis", "交易异常排查", "排查订单失败、履约异常、取消退款异常。"),
                            new SubDomainCandidate("trade_knowledge", "交易规则解释", "解释交易链路、状态流转和规则。")
                    )
            ),
            new DomainCandidate(
                    "marketing",
                    "营销域",
                    "处理优惠券、活动、限额、预算、权益、动态规则、营销资格和优惠叠加等问题。",
                    List.of(
                            new SubDomainCandidate("marketing_status", "营销资格查询", "查询用户、订单或活动的优惠资格、限额占用和权益状态。"),
                            new SubDomainCandidate("marketing_diagnosis", "营销异常排查", "排查优惠不可用、限额不足、规则未命中、预算冻结等问题。"),
                            new SubDomainCandidate("marketing_knowledge", "营销规则解释", "解释优惠叠加、限额、活动预算、动态规则配置。")
                    )
            )
    );

    public List<DomainCandidate> enabledDomains() {
        return domains;
    }

    public boolean contains(String domainCode, String subDomainCode) {
        return domains.stream()
                .filter(d -> d.domainCode().equals(domainCode))
                .flatMap(d -> d.subDomains().stream())
                .anyMatch(s -> s.subDomainCode().equals(subDomainCode));
    }

    public DomainCandidate findDomain(String domainCode) {
        return domains.stream()
                .filter(d -> d.domainCode().equals(domainCode))
                .findFirst()
                .orElse(null);
    }
}
