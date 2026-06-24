package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class MarketingQueryTool implements AiTool {
    public static final String CODE = "marketing_query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CODE, "营销资格查询", "按订单号/checkoutId 和环境查询优惠资格、限额、预算和规则命中情况", List.of("env", "objectId"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String objectId = String.valueOf(request.args().get("objectId"));
        String env = String.valueOf(request.args().get("env"));
        boolean eligible = Math.abs(objectId.hashCode()) % 4 != 0;
        Map<String, Object> data = Map.of(
                "objectId", objectId,
                "env", env,
                "eligible", eligible,
                "activityStatus", "ONLINE",
                "budgetStatus", eligible ? "AVAILABLE" : "BUDGET_LOCKED",
                "limitStatus", eligible ? "PASS" : "USER_LIMIT_EXCEEDED",
                "ruleHit", eligible ? "满减活动命中" : "用户限额未通过",
                "queryTime", Instant.now().toString()
        );
        String summary = "营销对象 " + objectId + " 在 " + env + " 环境优惠资格为 "
                + (eligible ? "可用" : "不可用，原因可能是用户限额或预算冻结") + "。";
        return ToolExecutionResult.success("营销资格查询结果", summary, data);
    }
}
