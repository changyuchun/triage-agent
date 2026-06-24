package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付状态查询工具——对接支付域内部 API（paycenter/risk 等微服务）。
 * <p>
 * 激活条件在 payment-diagnosis/SKILL.md 的 activate_when 声明，此类只负责执行。
 */
@Component
public class PaymentQueryTool implements AiTool {

    public static final String CODE = "payment_query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CODE,
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
        data.put("env", env);
        data.put("status", inferStatus(payOrderId));
        data.put("errorCode", "FAILED".equals(data.get("status")) ? "PAY_TIMEOUT" : "");
        data.put("errorMsg", "FAILED".equals(data.get("status"))
                ? "支付渠道响应超时（耗时 > 3000ms），已触发超时熔断" : "");
        data.put("riskCode", "FAILED".equals(data.get("status")) ? "PASS" : "");
        data.put("channel", "alipay");
        data.put("channelOrderId", "alipay-" + payOrderId.hashCode());
        data.put("amount", 9900);
        data.put("currency", "CNY");
        data.put("retryCount", "FAILED".equals(data.get("status")) ? 2 : 0);

        String status = (String) data.get("status");
        String summary = String.format("支付单 %s 状态: %s，渠道: alipay，环境: %s%s",
                payOrderId, status, env,
                "FAILED".equals(status) ? "，错误码: PAY_TIMEOUT，已重试 2 次" : "");

        return ToolExecutionResult.success("支付单查询结果", summary, data);
    }

    private String inferStatus(String payOrderId) {
        String upper = payOrderId.toUpperCase();
        if (upper.contains("FAIL") || upper.contains("RISK") || Math.abs(payOrderId.hashCode()) % 3 == 0) {
            return "FAILED";
        }
        if (Math.abs(payOrderId.hashCode()) % 5 == 0) {
            return "PROCESSING";
        }
        return "SUCCESS";
    }
}
