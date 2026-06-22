package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {
    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AiTool> toolList) {
        for (AiTool tool : toolList) {
            tools.put(tool.definition().code(), tool);
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AiTool::definition).toList();
    }

    public ToolExecutionResult call(String toolCode, Map<String, Object> args) {
        AiTool tool = tools.get(toolCode);
        if (tool == null) {
            return ToolExecutionResult.failed("TOOL_NOT_FOUND", "未注册工具: " + toolCode);
        }
        for (String required : tool.definition().requiredFields()) {
            Object value = args.get(required);
            if (value == null || value.toString().isBlank()) {
                return ToolExecutionResult.failed("TOOL_PARAM_MISSING", "缺少工具参数: " + required);
            }
        }
        try {
            return tool.execute(new ToolExecutionRequest(toolCode, args));
        } catch (Exception e) {
            return ToolExecutionResult.failed("TOOL_EXCEPTION", e.getMessage());
        }
    }
}
