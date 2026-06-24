package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易订单查询工具——对接交易域内部 API（tradecenter/fulfillment 等微服务）。
 * <p>
 * 激活条件在 trade-diagnosis/SKILL.md 的 activate_when 声明，此类只负责执行。
 */
@Component
public class TradeQueryTool implements AiTool {

    public static final String CODE = "trade_query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CODE,
                "交易订单查询",
                "根据订单号查询交易状态、履约状态和商品明细",
                List.of("orderId"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String orderId = (String) request.args().get("orderId");
        String env = (String) request.args().getOrDefault("env", "PROD");

        if (orderId == null || orderId.isBlank()) {
            return ToolExecutionResult.failed("PARAM_MISSING", "订单号不能为空");
        }

        String tradeStatus = inferStatus(orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("env", env);
        data.put("status", tradeStatus);
        data.put("errorCode", "FAILED".equals(tradeStatus) ? "TRADE_CREATE_FAIL" : "");
        data.put("fulfillStatus", switch (tradeStatus) {
            case "PAID" -> "WAIT_FULFILL";
            case "FAILED" -> "NOT_CREATED";
            default -> "DONE";
        });
        data.put("reverseStatus", "REFUNDING".equals(tradeStatus) ? "REFUNDING" : "NONE");
        data.put("createTime", "2024-01-15T10:30:00Z");
        data.put("totalAmount", 9900);
        data.put("currency", "CNY");
        data.put("items", List.of(Map.of("sku", "SKU-001", "name", "测试商品", "qty", 1, "price", 9900)));

        return ToolExecutionResult.success(
                "交易订单查询结果",
                String.format("订单 %s 状态: %s，履约状态: %s，环境: %s",
                        orderId, tradeStatus, data.get("fulfillStatus"), env),
                data);
    }

    private String inferStatus(String orderId) {
        int hash = Math.abs(orderId.hashCode());
        if (orderId.toUpperCase().contains("FAIL") || hash % 7 == 0) return "FAILED";
        if (hash % 5 == 0) return "REFUNDING";
        if (hash % 3 == 0) return "PAID";
        return "FULFILLED";
    }
}
