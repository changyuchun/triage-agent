package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class TradeQueryTool implements AiTool {
    public static final String CODE = "trade.query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CODE, "订单查询", "按订单号/checkoutId 和环境查询订单、生单、履约、退款状态。", List.of("env", "objectId"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String objectId = String.valueOf(request.args().get("objectId"));
        String env = String.valueOf(request.args().get("env"));
        String status = inferStatus(objectId);
        Map<String, Object> data = Map.of(
                "objectId", objectId,
                "env", env,
                "tradeStatus", status,
                "fulfillmentStatus", status.equals("PAID") ? "WAIT_FULFILL" : status.equals("FAILED") ? "NOT_CREATED" : "DONE",
                "reverseStatus", status.equals("REFUNDING") ? "REFUNDING" : "NONE",
                "errorCode", status.equals("FAILED") ? "TRADE_CREATE_FAIL" : "",
                "queryTime", Instant.now().toString()
        );
        String summary = "交易对象 " + objectId + " 在 " + env + " 环境订单状态为 " + status + "。";
        return ToolExecutionResult.success("订单查询结果", summary, data);
    }

    private String inferStatus(String objectId) {
        int hash = Math.abs(objectId.hashCode());
        if (objectId.toUpperCase().contains("FAIL") || hash % 7 == 0) {
            return "FAILED";
        }
        if (hash % 5 == 0) {
            return "REFUNDING";
        }
        if (hash % 3 == 0) {
            return "PAID";
        }
        return "FULFILLED";
    }
}
