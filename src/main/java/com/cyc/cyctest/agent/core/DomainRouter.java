package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.ClarifyLlmResult;
import com.cyc.cyctest.agent.core.AgentModels.ProblemType;
import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.domain.DomainModels.DomainCandidate;
import com.cyc.cyctest.agent.domain.DomainModels.SubDomainCandidate;
import com.cyc.cyctest.agent.domain.DomainRegistry;
import com.cyc.cyctest.agent.llm.JsonSupport;
import com.cyc.cyctest.agent.llm.LlmClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DomainRouter {
    private final DomainRegistry domainRegistry;
    private final LlmClient llmClient;
    private final JsonSupport jsonSupport;

    public DomainRouter(DomainRegistry domainRegistry, LlmClient llmClient, JsonSupport jsonSupport) {
        this.domainRegistry = domainRegistry;
        this.llmClient = llmClient;
        this.jsonSupport = jsonSupport;
    }

    public RouteResult route(String userText, ClarifyLlmResult clarify, SlotState slots) {
        if (!llmClient.available()) {
            return heuristicRoute(userText, clarify, slots);
        }
        List<DomainCandidate> candidates = domainRegistry.enabledDomains();
        String system = "你是智能答疑 Agent 的领域路由模块，只能选择候选领域，只输出 JSON。";
        String user = """
                从候选领域中选择最合适的领域和子域。
                handleMode 只能是 knowledge_only, tool_only, knowledge_and_tool, clarify_required, unsupported。
                禁止生成候选外的 domainCode/subDomainCode。

                用户输入:
                %s

                对话挖掘:
                %s

                槽位:
                %s

                规则信号:
                %s

                候选领域:
                %s

                输出 JSON:
                {"domainCode":"payment","domainName":"支付域","subDomainCode":"pay_diagnosis","subDomainName":"支付失败排查","handleMode":"knowledge_and_tool","confidence":0.8,"reason":"..."}
                """.formatted(userText, clarify, slots, ruleHint(userText, slots), jsonSupport.write(candidates));
        try {
            RouteResult raw = jsonSupport.readJsonObject(llmClient.complete(system, user), RouteResult.class);
            return validate(raw, userText, clarify, slots);
        } catch (Exception e) {
            return heuristicRoute(userText, clarify, slots);
        }
    }

    private RouteResult validate(RouteResult raw, String userText, ClarifyLlmResult clarify, SlotState slots) {
        if (raw == null || !domainRegistry.contains(raw.domainCode(), raw.subDomainCode())) {
            return heuristicRoute(userText, clarify, slots);
        }
        if (raw.confidence() < 0.55) {
            return new RouteResult(raw.domainCode(), raw.domainName(), raw.subDomainCode(), raw.subDomainName(),
                    "clarify_required", raw.confidence(), raw.reason());
        }
        return raw;
    }

    private RouteResult heuristicRoute(String userText, ClarifyLlmResult clarify, SlotState slots) {
        String text = userText == null ? "" : userText;
        int payment = 0;
        int trade = 0;
        int marketing = 0;
        if (AgentModels.hasText(slots.payOrderId())) {
            payment += 5;
        }
        if (text.contains("支付") || text.contains("扣款") || text.contains("渠道") || text.contains("风控")) {
            payment += 4;
        }
        if (AgentModels.hasText(slots.orderId())) {
            trade += 2;
        }
        if (text.contains("订单") || text.contains("履约") || text.contains("取消") || text.contains("退款") || text.contains("购物车")) {
            trade += 3;
        }
        if (text.contains("优惠") || text.contains("券") || text.contains("营销") || text.contains("限额")
                || text.contains("预算") || text.contains("权益") || text.contains("活动")) {
            marketing += 5;
        }

        String domainCode = "payment";
        if (trade > payment && trade >= marketing) {
            domainCode = "trade";
        } else if (marketing > payment && marketing > trade) {
            domainCode = "marketing";
        }
        DomainCandidate domain = domainRegistry.findDomain(domainCode);
        String subCode = chooseSubDomain(domainCode, clarify.problemType());
        SubDomainCandidate sub = domain.subDomains().stream()
                .filter(s -> s.subDomainCode().equals(subCode))
                .findFirst()
                .orElse(domain.subDomains().getFirst());
        return new RouteResult(domain.domainCode(), domain.domainName(), sub.subDomainCode(), sub.subDomainName(),
                handleMode(clarify.problemType()), 0.70, "local route: " + ruleHint(userText, slots));
    }

    private String chooseSubDomain(String domainCode, ProblemType type) {
        if (domainCode.equals("payment")) {
            if (type == ProblemType.KNOWLEDGE_EXPLANATION) {
                return "pay_knowledge";
            }
            if (type == ProblemType.STATUS_QUERY) {
                return "pay_status";
            }
            return "pay_diagnosis";
        }
        if (domainCode.equals("marketing")) {
            if (type == ProblemType.KNOWLEDGE_EXPLANATION) {
                return "marketing_knowledge";
            }
            if (type == ProblemType.STATUS_QUERY) {
                return "marketing_status";
            }
            return "marketing_diagnosis";
        }
        if (type == ProblemType.KNOWLEDGE_EXPLANATION) {
            return "trade_knowledge";
        }
        if (type == ProblemType.STATUS_QUERY) {
            return "trade_status";
        }
        return "trade_diagnosis";
    }

    private String handleMode(ProblemType type) {
        return switch (type) {
            case KNOWLEDGE_EXPLANATION -> "knowledge_only";
            case STATUS_QUERY -> "tool_only";
            case DIAGNOSIS -> "knowledge_and_tool";
            case CONFIG_QUERY -> "knowledge_and_tool";
            case UNKNOWN -> "clarify_required";
        };
    }

    private String ruleHint(String text, SlotState slots) {
        return "hasPayOrderId=" + AgentModels.hasText(slots.payOrderId())
                + ", hasOrderId=" + AgentModels.hasText(slots.orderId())
                + ", env=" + slots.env()
                + ", text=" + text;
    }
}
