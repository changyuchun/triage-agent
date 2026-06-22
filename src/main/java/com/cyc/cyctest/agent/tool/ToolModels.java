package com.cyc.cyctest.agent.tool;

import java.util.List;
import java.util.Map;

public final class ToolModels {
    private ToolModels() {
    }

    public record ToolDefinition(
            String code,
            String name,
            String description,
            List<String> requiredFields
    ) {
    }

    public record ToolExecutionRequest(
            String toolCode,
            Map<String, Object> args
    ) {
    }

    public record ToolExecutionResult(
            boolean success,
            String title,
            String summary,
            Map<String, Object> data,
            String errorCode,
            String errorMessage
    ) {
        public static ToolExecutionResult success(String title, String summary, Map<String, Object> data) {
            return new ToolExecutionResult(true, title, summary, data, null, null);
        }

        public static ToolExecutionResult failed(String errorCode, String errorMessage) {
            return new ToolExecutionResult(false, "工具调用失败", errorMessage, Map.of(), errorCode, errorMessage);
        }
    }
}
