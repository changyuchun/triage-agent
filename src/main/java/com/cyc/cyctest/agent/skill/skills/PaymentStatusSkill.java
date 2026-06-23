package com.cyc.cyctest.agent.skill.skills;

import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.skill.AgentSkill;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付状态查询 Skill：替换原有 PaymentQueryTool，增加 Skill 分类和版本元数据。
 * 生产中对接支付域内部 API（paycenter/risk 等微服务）。
 */
@Component
public class PaymentStatusSkill implements AgentSkill {

    @Override
    public String skillId() {
        return "payment_query";
    }

    @Override
    public String category() {
        return "payment";
    }

    @Override
    public boolean shouldActivate(RouteResult route, SlotState slots) {
        // 支付域、有具体查询对象（支付单/订单号）、handleMode 需要工具调用
        return "payment".equals(route.domainCode())
                && slots.hasObjectId()
                && (route.handleMode().contains("tool"));
    }

    @Override
    public boolean requiresKnowledge() {
        return true; // 支付诊断通常需要配合 SOP/错误码知识库
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "payment_query",
                "支付状态查询",
                "根据支付单号查询支付状态、渠道信息、风控结果和失败原因",
                List.of("payOrderId"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String payOrderId = (String) request.args().get("payOrderId");
        String env = (String) request.args().getOrDefault("env", "PROD");
        if (payOrderId == null || payOrderId.isBlank()) {
            return ToolExecutionResult.failed("PARAM_MISSING", "支付单号不能为空");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payOrderId", payOrderId);
        data.put("status", "FAILED");
        data.put("errorCode", "PAY_TIMEOUT");
        data.put("errorMsg", "支付渠道响应超时（耗时 > 3000ms），已触发超时熔断");
        data.put("riskCode", "PASS");
        data.put("channel", "alipay");
        data.put("channelOrderId", "alipay-" + payOrderId.hashCode());
        data.put("amount", 9900);
        data.put("currency", "CNY");
        data.put("env", env);
        data.put("retryCount", 2);
        return ToolExecutionResult.success(
                "支付单查询结果",
                String.format("支付单 %s 状态: FAILED，原因: PAY_TIMEOUT，渠道: alipay，已重试 2 次", payOrderId),
                data);
    }
}
