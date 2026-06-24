package com.cyc.cyctest.agent.mcp;

import com.cyc.cyctest.agent.skill.SkillRegistry;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 工具适配器——将 ToolRegistry 中的 AiTool 暴露为 Spring AI @Tool 方法。
 * <p>
 * 替代原 SkillMcpConfig + 各 Skill 类上的 @Tool 注解方法。
 * AiTool 实现类不再需要 Spring AI 依赖，保持纯净的执行接口。
 * <p>
 * 新增工具时：在 ToolRegistry（AiTool @Component）加实现，在此类加 @Tool 方法委托即可。
 */
@Component
public class ToolCallbackAdapter {

    private final SkillRegistry skillRegistry;

    public ToolCallbackAdapter(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Tool(description = "根据支付单号查询支付订单状态、渠道信息、风控结果和失败原因。适用于支付超时、支付失败、重复扣款等问题的排查。")
    public String payment_query(
            @ToolParam(description = "支付单号，如 P20240101001") String payOrderId,
            @ToolParam(description = "环境：PROD/PRE/UAT，默认 PROD") String env) {
        return call("payment_query", Map.of(
                "payOrderId", nvl(payOrderId),
                "env", nvl(env, "PROD")));
    }

    @Tool(description = "根据订单号查询交易订单状态、履约配送状态和商品明细。适用于订单未支付、发货延迟等问题排查。")
    public String trade_query(
            @ToolParam(description = "交易订单号，如 ORDER-20240101-001") String orderId,
            @ToolParam(description = "环境：PROD/PRE/UAT，默认 PROD") String env) {
        return call("trade_query", Map.of(
                "orderId", nvl(orderId),
                "env", nvl(env, "PROD")));
    }

    @Tool(description = "查询应用运行日志，支持按关键词、时间段、错误级别过滤。适用于排查线上异常、定位错误根因。")
    public String log_query(
            @ToolParam(description = "查询关键词，如 traceId、错误码、payOrderId") String keyword,
            @ToolParam(description = "时间范围：15m/1h/6h/1d，默认 1h") String timeRange,
            @ToolParam(description = "日志级别：ERROR/WARN/INFO，默认 ERROR") String level) {
        return call("log_query", Map.of(
                "keyword", nvl(keyword),
                "timeRange", nvl(timeRange, "1h"),
                "level", nvl(level, "ERROR")));
    }

    @Tool(description = "按订单号/checkoutId 查询营销优惠资格、限额、预算和规则命中情况。")
    public String marketing_query(
            @ToolParam(description = "订单号或 checkoutId") String objectId,
            @ToolParam(description = "环境：PROD/PRE/UAT，默认 PROD") String env) {
        return call("marketing_query", Map.of(
                "objectId", nvl(objectId),
                "env", nvl(env, "PROD")));
    }

    private String call(String toolCode, Map<String, Object> args) {
        return skillRegistry.toolFor(toolCode)
                .map(tool -> {
                    ToolExecutionResult r = tool.execute(
                            new com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest(toolCode, args));
                    return r.success() ? r.summary()
                            : "工具调用失败[" + r.errorCode() + "]: " + r.errorMessage();
                })
                .orElse("未找到工具: " + toolCode);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String nvl(String s, String defaultVal) {
        return (s != null && !s.isBlank()) ? s : defaultVal;
    }
}
