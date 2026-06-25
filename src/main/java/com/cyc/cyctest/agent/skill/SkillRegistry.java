package com.cyc.cyctest.agent.skill;

import com.cyc.cyctest.agent.tool.AiTool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 唯一 Skill 注册表——合并原 AgentSkillRegistry（Java 对象层）和 DomainSkillLoader（文件层）。
 * <p>
 * 职责：
 * 1. 加载 classpath:skills/SLASH/SLASH/SKILL.md，解析 frontmatter → SkillMetadata
 * 2. 持有所有 AiTool 实现（Spring 自动注入），按 toolCode 索引
 * 3. 提供 findActivatable()、sopFor()、toolFlowFor() 供上层服务查询
 * <p>
 * SKILL.md 是单一真相来源：domain/subDomain/激活规则/工具流程/SOP 全在文件里，
 * AiTool Java 类只负责执行，不知道自己属于哪个领域。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String DELIMITER = "---";

    // key = "domain|subDomain" 或 "domain"，crossDomain 用 "*"
    private final Map<String, SkillMetadata> metaByKey = new LinkedHashMap<>();
    private SkillMetadata crossDomainMeta = null;

    // toolCode → AiTool 执行实现
    private final Map<String, AiTool> toolByCode;

    public SkillRegistry(List<AiTool> tools) {
        this.toolByCode = tools.stream()
                .collect(Collectors.toMap(
                        t -> t.definition().code(),
                        t -> t,
                        (a, b) -> a,        // 同 code 保留先注册的
                        LinkedHashMap::new));
    }

    @PostConstruct
    public void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
            for (Resource resource : resources) {
                parseAndRegister(resource);
            }
            log.info("SkillRegistry: 加载 {} 个 SKILL.md，工具 {} 个，跨领域: {}",
                    metaByKey.size(), toolByCode.size(),
                    crossDomainMeta != null ? crossDomainMeta.name() : "无");
        } catch (IOException e) {
            log.warn("SkillRegistry: SKILL.md 加载失败: {}", e.getMessage());
        }
    }

    // ── 规划层 ───────────────────────────────────────────────────────────

    /**
     * 返回当前路由和槽位下所有激活的 SkillMetadata。
     * TaskPlanner.rulePlan() 用此列表构建工具步骤（直接用 tool_flow，不再硬编码）。
     */
    public List<SkillMetadata> findActivatable(ActivationContext ctx) {
        List<SkillMetadata> result = new ArrayList<>();
        for (SkillMetadata meta : metaByKey.values()) {
            if (meta.activationRule() != null && meta.activationRule().matches(ctx)) {
                result.add(meta);
            }
        }
        // 跨领域 Skill 单独判断（如 log-query）
        if (crossDomainMeta != null && crossDomainMeta.activationRule() != null
                && crossDomainMeta.activationRule().matches(ctx)) {
            result.add(crossDomainMeta);
        }
        return Collections.unmodifiableList(result);
    }

    public Optional<SkillMetadata> findByName(String name) {
        if (name == null) return Optional.empty();
        return metaByKey.values().stream()
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .or(() -> crossDomainMeta != null && name.equals(crossDomainMeta.name())
                        ? Optional.of(crossDomainMeta) : Optional.empty());
    }

    public List<SkillMetadata> allSkills() {
        List<SkillMetadata> all = new ArrayList<>(metaByKey.values());
        if (crossDomainMeta != null) all.add(crossDomainMeta);
        return Collections.unmodifiableList(all);
    }

    /**
     * 返回当前领域+子域下所有 SkillMetadata（不校验激活条件），
     * 供 llmPlan() 把工具定义和 tool_flow 给 LLM 参考。
     */
    public List<SkillMetadata> findByDomain(String domain, String subDomain) {
        List<SkillMetadata> result = new ArrayList<>();
        // 精确子域匹配
        String subKey = (subDomain != null && !subDomain.isBlank()) ? domain + "|" + subDomain : null;
        if (subKey != null && metaByKey.containsKey(subKey)) {
            result.add(metaByKey.get(subKey));
        }
        // 领域级匹配（无子域字段的 SKILL.md）
        if (metaByKey.containsKey(domain)) {
            result.add(metaByKey.get(domain));
        }
        // 跨领域
        if (crossDomainMeta != null) {
            result.add(crossDomainMeta);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 所有 tool_flow 里引用的 AiTool 定义，供 llmPlan() 把工具 schema 给 LLM。
     */
    public List<com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition> toolDefinitionsFor(
            String domain, String subDomain) {
        Set<String> codes = new LinkedHashSet<>();
        for (SkillMetadata meta : findByDomain(domain, subDomain)) {
            for (ToolFlowStep step : meta.toolFlow()) {
                codes.add(step.toolCode());
            }
        }
        return codes.stream()
                .map(toolByCode::get)
                .filter(Objects::nonNull)
                .map(AiTool::definition)
                .collect(Collectors.toList());
    }

    // ── 合成层 ───────────────────────────────────────────────────────────

    /**
     * 返回指定领域+子域的 SOP 内容，只注入 AnswerSynthesizer 的 System Prompt。
     * 查找优先级：精确子域 → 领域级 → 空；末尾附加跨领域 SOP。
     */
    public String sopFor(String domain, String subDomain) {
        if (domain == null || domain.isBlank()) {
            return crossDomainMeta != null ? crossDomainMeta.sopContent() : "";
        }
        String subKey = (subDomain != null && !subDomain.isBlank()) ? domain + "|" + subDomain : null;
        String sop = subKey != null ? sopFromKey(subKey) : null;
        if (sop == null) sop = sopFromKey(domain);
        if (sop == null) sop = "";
        String cross = crossDomainMeta != null ? crossDomainMeta.sopContent() : "";
        if (cross.isBlank()) return sop;
        return sop.isBlank() ? cross : sop + "\n\n---\n\n" + cross;
    }

    private String sopFromKey(String key) {
        SkillMetadata meta = metaByKey.get(key);
        return meta != null ? meta.sopContent() : null;
    }

    // ── 执行层 ───────────────────────────────────────────────────────────

    public Optional<AiTool> toolFor(String toolCode) {
        return Optional.ofNullable(toolByCode.get(toolCode));
    }

    public Map<String, AiTool> allTools() {
        return Collections.unmodifiableMap(toolByCode);
    }

    // ── 加载 ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void parseAndRegister(Resource resource) {
        try {
            String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int first = raw.indexOf(DELIMITER);
            int second = first >= 0 ? raw.indexOf(DELIMITER, first + 3) : -1;
            if (first < 0 || second < 0) return;

            String yamlText = raw.substring(first + 3, second);
            String body = raw.substring(second + 3).trim();

            Yaml yaml = new Yaml();
            Map<String, Object> fm = yaml.load(yamlText);
            if (fm == null) return;

            String name = str(fm, "name");
            String description = str(fm, "description");
            String domain = str(fm, "domain");
            String subDomain = str(fm, "sub_domain");
            boolean requiresKnowledge = Boolean.TRUE.equals(fm.get("requires_knowledge"));

            if (domain == null) {
                log.debug("SkillRegistry: 跳过 {} (缺少 domain)", resource.getFilename());
                return;
            }

            // 解析 activate_when
            ActivationRule activationRule = null;
            if (fm.containsKey("activate_when")) {
                Map<String, Object> aw = (Map<String, Object>) fm.get("activate_when");
                String awDomain = str(aw, "domain");
                List<String> awSubDomains = toStringList(aw.get("sub_domain"));
                List<String> awRequires = toStringList(aw.get("requires"));
                activationRule = new ActivationRule(
                        awDomain != null ? awDomain : domain,
                        awSubDomains,
                        awRequires);
            }

            // 解析 tool_flow
            List<ToolFlowStep> toolFlow = new ArrayList<>();
            if (fm.containsKey("tool_flow")) {
                List<Map<String, Object>> flowList = (List<Map<String, Object>>) fm.get("tool_flow");
                if (flowList != null) {
                    for (Map<String, Object> step : flowList) {
                        String stepId = str(step, "stepId");
                        String toolCode = str(step, "toolCode");
                        if (stepId == null || toolCode == null) continue;
                        Map<String, Object> rawArgs = step.containsKey("args")
                                ? (Map<String, Object>) step.get("args") : Map.of();
                        Map<String, String> args = new LinkedHashMap<>();
                        rawArgs.forEach((k, v) -> args.put(k, String.valueOf(v)));
                        List<String> dependsOn = toStringList(step.get("dependsOn"));
                        String condition = str(step, "condition");
                        boolean required = Boolean.TRUE.equals(step.get("required"));
                        toolFlow.add(new ToolFlowStep(stepId, toolCode, args, dependsOn, condition, required));
                    }
                }
            }

            SkillMetadata meta = new SkillMetadata(name, description, domain, subDomain,
                    "*".equals(domain), requiresKnowledge, activationRule, toolFlow, body);

            if ("*".equals(domain)) {
                crossDomainMeta = meta;
            } else if (subDomain != null && !subDomain.isBlank()) {
                metaByKey.put(domain + "|" + subDomain, meta);
            } else {
                metaByKey.put(domain, meta);
            }
            log.debug("SkillRegistry: 注册 skill={}, domain={}, subDomain={}, toolFlow={}步",
                    name, domain, subDomain, toolFlow.size());
        } catch (Exception e) {
            log.warn("SkillRegistry: 解析 {} 失败: {}", resource.getFilename(), e.getMessage());
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(Collectors.toList());
        }
        return List.of(raw.toString());
    }
}
