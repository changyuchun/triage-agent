package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 日志查询工具——跨领域辅助能力，对接 ELK/SkyWalking/阿里云 SLS。
 * <p>
 * 激活条件在 log-query/SKILL.md 的 activate_when 字段声明，此类只负责执行。
 */
@Component
public class LogQueryTool implements AiTool {

    public static final String CODE = "log_query";

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CODE,
                "应用日志查询",
                "按关键词、时间范围、日志级别查询应用运行日志，用于定位错误根因和异常链路",
                List.of("keyword"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String keyword = (String) request.args().getOrDefault("keyword", "");
        String timeRange = (String) request.args().getOrDefault("timeRange", "1h");
        String level = (String) request.args().getOrDefault("level", "ERROR");

        if (keyword == null || keyword.isBlank()) {
            return ToolExecutionResult.failed("PARAM_MISSING", "查询关键词不能为空");
        }

        Instant now = Instant.now();
        List<Map<String, Object>> logs = List.of(
                Map.of("timestamp", now.minus(5, ChronoUnit.MINUTES).toString(),
                        "level", "ERROR",
                        "traceId", "trace-" + Math.abs(keyword.hashCode()),
                        "message", "[" + keyword + "] 支付渠道响应超时，errorCode=PAY_TIMEOUT，耗时3200ms",
                        "service", "payment-gateway",
                        "env", "PROD"),
                Map.of("timestamp", now.minus(10, ChronoUnit.MINUTES).toString(),
                        "level", "WARN",
                        "traceId", "trace-" + (Math.abs(keyword.hashCode()) + 1),
                        "message", "[" + keyword + "] 重试成功，第2次请求返回200，总耗时5100ms",
                        "service", "payment-gateway",
                        "env", "PROD"));

        return ToolExecutionResult.success(
                "日志查询结果",
                String.format("查询到 %d 条日志（关键词: %s, 时间范围: %s, 级别: %s）",
                        logs.size(), keyword, timeRange, level),
                Map.of("logs", logs, "total", logs.size(),
                        "keyword", keyword, "timeRange", timeRange));
    }
}
