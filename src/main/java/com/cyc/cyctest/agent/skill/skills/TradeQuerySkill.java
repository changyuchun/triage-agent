package com.cyc.cyctest.agent.skill.skills;

import com.cyc.cyctest.agent.skill.AgentSkill;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 交易订单查询 Skill：替换原有 TradeQueryTool，增加 Skill 分类和版本元数据。
 * 生产中对接交易域内部 API（tradecenter/fulfillment 等微服务）。
 */
@Component
public class TradeQuerySkill implements AgentSkill {

    @Override
    public String skillId() {
        return "trade_query";
    }

    @Override
    public String category() {
        return "trade";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "trade_query",
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
        Map<String, Object> data = Map.of(
                "orderId", orderId,
                "status", "PAID",
                "fulfillStatus", "DELIVERING",
                "createTime", "2024-01-15T10:30:00Z",
                "payTime", "2024-01-15T10:31:05Z",
                "items", List.of(
                        Map.of("sku", "SKU-001", "name", "测试商品", "qty", 1, "price", 9900)),
                "totalAmount", 9900,
                "currency", "CNY",
                "env", env);
        return ToolExecutionResult.success(
                "交易订单查询结果",
                String.format("订单 %s 状态: PAID，履约状态: DELIVERING，总金额: ¥99.00", orderId),
                data);
    }
}
