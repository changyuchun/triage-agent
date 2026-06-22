package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class PaymentQueryTool implements AiTool {
    public static final String CODE = "payment.query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CODE, "支付单查询", "按支付单号/订单号/checkoutId 和环境查询支付状态。", List.of("env", "objectId"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String objectId = String.valueOf(request.args().get("objectId"));
        String env = String.valueOf(request.args().get("env"));
        String status = inferStatus(objectId);
        Map<String, Object> data = Map.of(
                "objectId", objectId,
                "env", env,
                "payStatus", status,
                "channel", objectId.hashCode() % 2 == 0 ? "ALIPAY" : "BANK_CARD",
                "riskCode", status.equals("FAILED") ? "RISK_REJECT" : "",
                "channelResult", status.equals("FAILED") ? "风控拒绝" : "支付成功",
                "queryTime", Instant.now().toString()
        );
        String summary = "支付对象 " + objectId + " 在 " + env + " 环境状态为 " + status
                + (status.equals("FAILED") ? "，风险码 RISK_REJECT。" : "。");
        return ToolExecutionResult.success("支付单查询结果", summary, data);
    }

    private String inferStatus(String objectId) {
        String upper = objectId.toUpperCase();
        if (upper.contains("FAIL") || upper.contains("RISK") || Math.abs(objectId.hashCode()) % 3 == 0) {
            return "FAILED";
        }
        if (Math.abs(objectId.hashCode()) % 5 == 0) {
            return "PROCESSING";
        }
        return "SUCCESS";
    }
}
