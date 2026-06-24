package com.cyc.cyctest.agent.skill;

import java.util.List;

/**
 * SKILL.md activate_when 字段的结构化表示。
 * <p>
 * requires 支持的谓词：
 * - "slots.hasObjectId"    槽位中有业务对象 ID
 * - "route.needsTool"      handleMode 包含 "tool"
 * - "slots.hasErrorCode"   槽位中有 errorCode
 * - "route.isDiagnosis"    subDomainCode 以 _diagnosis 结尾
 */
public record ActivationRule(
        String domain,              // 匹配的领域 code，"*" 表示跨领域
        List<String> subDomains,    // 匹配的子域 code 列表，空表示匹配所有子域
        List<String> requires       // 必须满足的谓词列表（AND 关系）
) {
    public ActivationRule {
        subDomains = subDomains != null ? List.copyOf(subDomains) : List.of();
        requires = requires != null ? List.copyOf(requires) : List.of();
    }

    public boolean matches(ActivationContext ctx) {
        // 领域匹配（"*" 跨领域不校验 domain）
        if (!"*".equals(domain) && !domain.equals(ctx.route().domainCode())) {
            return false;
        }
        // 子域匹配（空列表 = 匹配所有子域）
        if (!subDomains.isEmpty() && !subDomains.contains(ctx.route().subDomainCode())) {
            return false;
        }
        // 所有谓词必须满足
        for (String req : requires) {
            if (!evalPredicate(req, ctx)) return false;
        }
        return true;
    }

    private boolean evalPredicate(String predicate, ActivationContext ctx) {
        return switch (predicate) {
            case "slots.hasObjectId" -> ctx.slots().hasObjectId();
            case "route.needsTool" -> ctx.route().handleMode() != null
                    && ctx.route().handleMode().contains("tool");
            case "slots.hasErrorCode" -> ctx.slots().errorCode() != null
                    && !ctx.slots().errorCode().isBlank();
            case "route.isDiagnosis" -> ctx.route().subDomainCode() != null
                    && ctx.route().subDomainCode().endsWith("_diagnosis");
            default -> true; // 未知谓词默认通过，不阻断激活
        };
    }
}
