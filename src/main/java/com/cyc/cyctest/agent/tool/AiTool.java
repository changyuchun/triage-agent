package com.cyc.cyctest.agent.tool;

import com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionRequest;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;

public interface AiTool {
    ToolDefinition definition();

    ToolExecutionResult execute(ToolExecutionRequest request);
}
