package com.cyc.cyctest.agent.skill.skills;

import com.cyc.cyctest.agent.core.AgentModels.RouteResult;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.skill.AgentSkill;
import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 日志查询 Skill：封装应用日志查询能力（生产中对接 ELK/SkyWalking/阿里云 SLS）。
 * <p>
 * 同时暴露为 MCP Tool（@Tool 注解），外部 MCP Client 可直接调用。
 */
@Component
public class LogQuerySkill implements AgentSkill {

    @Override
    public String skillId() {
        return "log_query";
    }

    @Override
    public String category() {
        return "observability";
    }

    @Override
    public boolean shouldActivate(RouteResult route, SlotState slots) {
        // 日志查询作为跨领域辅助能力：有 errorCode 槽位时补充日志证据
        return slots.errorCode() != null && !slots.errorCode().isBlank();
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "log_query",
                "应用日志查询",
                "按关键词、时间范围、日志级别查询应用运行日志，用于定位错误和异常",
                List.of("keyword"));
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        String keyword = (String) request.args().getOrDefault("keyword", "");
        String timeRange = (String) request.args().getOrDefault("timeRange", "1h");
        String level = (String) request.args().getOrDefault("level", "ERROR");
        return queryLogs(keyword, timeRange, level);
    }

    @Tool(description = "查询应用运行日志，支持按关键词、时间段、错误级别过滤。" +
            "适用于排查线上异常、定位错误根因。生产环境对接 ELK/SkyWalking。")
    public String queryLogsAsTool(
            @ToolParam(description = "查询关键词，如 traceId、错误码、payOrderId") String keyword,
            @ToolParam(description = "时间范围：15m/1h/6h/1d，默认 1h") String timeRange,
            @ToolParam(description = "日志级别：ERROR/WARN/INFO，默认 ERROR") String level) {
        ToolExecutionResult result = queryLogs(keyword, timeRange, level);
        if (!result.success()) {
            return "日志查询失败: " + result.errorMessage();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>) result.data().get("logs");
        if (logs == null || logs.isEmpty()) {
            return "未找到关键词 [" + keyword + "] 相关日志（时间范围: " + timeRange + ", 级别: " + level + "）";
        }
        StringBuilder sb = new StringBuilder("找到 ").append(logs.size()).append(" 条日志：\n");
        for (Map<String, Object> log : logs) {
            sb.append(String.format("[%s][%s][%s] %s%n",
                    log.get("timestamp"), log.get("level"), log.get("service"), log.get("message")));
        }
        return sb.toString();
    }

    private ToolExecutionResult queryLogs(String keyword, String timeRange, String level) {
        Instant now = Instant.now();
        List<Map<String, Object>> logs = List.of(
                Map.of(
                        "timestamp", now.minus(5, ChronoUnit.MINUTES).toString(),
                        "level", "ERROR",
                        "traceId", "trace-" + Math.abs(keyword.hashCode()),
                        "message", "[" + keyword + "] 支付渠道响应超时，errorCode=PAY_TIMEOUT，耗时3200ms",
                        "service", "payment-gateway",
                        "env", "PROD"),
                Map.of(
                        "timestamp", now.minus(10, ChronoUnit.MINUTES).toString(),
                        "level", "WARN",
                        "traceId", "trace-" + (Math.abs(keyword.hashCode()) + 1),
                        "message", "[" + keyword + "] 重试成功，第2次请求返回200，总耗时5100ms",
                        "service", "payment-gateway",
                        "env", "PROD"));
        String summary = String.format("查询到 %d 条日志（关键词: %s, 时间范围: %s, 级别: %s）",
                logs.size(), keyword, timeRange, level);
        return ToolExecutionResult.success("日志查询结果", summary,
                Map.of("logs", logs, "total", logs.size(), "keyword", keyword, "timeRange", timeRange));
    }
}
