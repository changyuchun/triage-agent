package com.cyc.cyctest.agent.core;

import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.tool.ToolModels.ToolExecutionResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAG 执行上下文：存储已完成步骤的结果，支持 ${stepId.field} 变量替换。
 * <p>
 * 实现 LLMCompiler 论文中的 Task Fetching Unit 模式：
 * 后续步骤在执行前先从此上下文解析参数，实现工具间结果传递。
 *
 * <pre>
 * 示例：
 *   payment_query_step 返回 data={errorCode:"PAY_TIMEOUT"}
 *   log_query_step 的 args = {"keyword": "${payment_query_step.errorCode}"}
 *   → resolveArgs() 将 keyword 解析为 "PAY_TIMEOUT"
 * </pre>
 */
public class StepResultContext {

    private final SlotState slots;
    /** stepId → 工具执行结果的 data Map */
    private final Map<String, Map<String, Object>> stepOutputs = new LinkedHashMap<>();

    public StepResultContext(SlotState slots) {
        this.slots = slots;
    }

    public void store(String stepId, ToolExecutionResult result) {
        Map<String, Object> data = result.data() != null ? result.data() : Map.of();
        // 把工具执行状态也放进去，供 condition 判断用
        Map<String, Object> enriched = new LinkedHashMap<>(data);
        enriched.put("status", result.success() ? "SUCCESS" : "FAILED");
        enriched.put("errorCode", result.errorCode());
        stepOutputs.put(stepId, enriched);
    }

    /**
     * 将模板参数（可能含 ${...} 占位符）解析为实际值。
     * 非字符串值或无占位符的直接原样返回。
     */
    public Map<String, Object> resolveArgs(Map<String, Object> templateArgs) {
        if (templateArgs == null || templateArgs.isEmpty()) return Map.of();
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : templateArgs.entrySet()) {
            resolved.put(entry.getKey(), resolve(entry.getValue()));
        }
        return resolved;
    }

    private Object resolve(Object value) {
        if (!(value instanceof String s)) return value;
        if (!s.startsWith("${") || !s.endsWith("}")) return value;
        String path = s.substring(2, s.length() - 1).trim();
        String[] parts = path.split("\\.", 2);
        if (parts.length < 2) return null;
        if ("slots".equals(parts[0])) return resolveSlot(parts[1]);
        Map<String, Object> stepData = stepOutputs.get(parts[0]);
        return stepData != null ? stepData.get(parts[1]) : null;
    }

    private Object resolveSlot(String field) {
        return switch (field) {
            case "payOrderId" -> slots.payOrderId();
            case "orderId" -> slots.orderId();
            case "checkoutId" -> slots.checkoutId();
            case "errorCode" -> slots.errorCode();
            case "env" -> slots.env() != null ? slots.env().name() : "PROD";
            case "activityId" -> slots.activityId();
            case "couponId" -> slots.couponId();
            default -> null;
        };
    }

    /**
     * 评估条件表达式，格式：{@code "${stepId.field} == VALUE"}。
     * 条件为空或 null 时默认返回 true（无条件执行）。
     */
    public boolean evaluateCondition(String condition) {
        if (condition == null || condition.isBlank()) return true;
        // 支持 "${step.field} == VALUE" 格式
        String[] parts = condition.split("==", 2);
        if (parts.length != 2) return true;
        Object lhs = resolve(parts[0].trim());
        String rhs = parts[1].trim().replace("\"", "").replace("'", "");
        return rhs.equals(String.valueOf(lhs));
    }

    public boolean hasResult(String stepId) {
        return stepOutputs.containsKey(stepId);
    }
}
