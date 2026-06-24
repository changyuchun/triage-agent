package com.cyc.cyctest.agent.skill;

import java.util.List;
import java.util.Map;

/**
 * SKILL.md tool_flow 中的单个步骤，对应 LLMCompiler DAG 里的一个 Task。
 * <p>
 * args 值支持变量引用语法：
 * - "${slots.payOrderId}"    从当前对话槽位取值
 * - "${step1.errorCode}"     从前序步骤的执行结果取值
 */
public record ToolFlowStep(
        String stepId,
        String toolCode,
        Map<String, String> args,       // 模板参数，值可含 ${...} 引用
        List<String> dependsOn,         // 前置步骤 ID 列表，控制执行顺序
        String condition,               // 可选条件，如 "${step1.status} == FAILED"
        boolean required
) {
    public ToolFlowStep {
        dependsOn = dependsOn != null ? List.copyOf(dependsOn) : List.of();
        args = args != null ? Map.copyOf(args) : Map.of();
    }

    public boolean hasDependencies() {
        return !dependsOn.isEmpty();
    }
}
