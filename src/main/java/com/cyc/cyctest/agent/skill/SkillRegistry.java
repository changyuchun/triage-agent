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
 * 唯一 Skill 注册表。
 * <p>
 * SKILL.md tool_flow 字段现在只是工具名列表（不含 DAG 结构），DAG 由 Plan 阶段 LLM 从 SOP 推断。
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final String DELIMITER = "---";

    private final Map<String, SkillMetadata> metaByKey = new LinkedHashMap<>();
    private SkillMetadata crossDomainMeta = null;

    private final Map<String, AiTool> toolByCode;

    public SkillRegistry(List<AiTool> tools) {
        this.toolByCode = tools.stream()
                .collect(Collectors.toMap(
                        t -> t.definition().code(),
                        t -> t,
                        (a, b) -> a,
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

    public List<SkillMetadata> findActivatable(ActivationContext ctx) {
        List<SkillMetadata> result = new ArrayList<>();
        for (SkillMetadata meta : metaByKey.values()) {
            if (meta.activationRule() != null && meta.activationRule().matches(ctx)) {
                result.add(meta);
            }
        }
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

    public List<SkillMetadata> findByDomain(String domain, String subDomain) {
        List<SkillMetadata> result = new ArrayList<>();
        String subKey = (subDomain != null && !subDomain.isBlank()) ? domain + "|" + subDomain : null;
        if (subKey != null && metaByKey.containsKey(subKey)) {
            result.add(metaByKey.get(subKey));
        }
        if (metaByKey.containsKey(domain)) {
            result.add(metaByKey.get(domain));
        }
        if (crossDomainMeta != null) {
            result.add(crossDomainMeta);
        }
        return Collections.unmodifiableList(result);
    }

    /** 返回当前领域+子域下所有 skill 声明的工具定义，供 Plan 阶段注入 LLM prompt。 */
    public List<com.cyc.cyctest.agent.tool.ToolModels.ToolDefinition> toolDefinitionsFor(
            String domain, String subDomain) {
        Set<String> codes = new LinkedHashSet<>();
        for (SkillMetadata meta : findByDomain(domain, subDomain)) {
            codes.addAll(meta.tools());
        }
        return codes.stream()
                .map(toolByCode::get)
                .filter(Objects::nonNull)
                .map(AiTool::definition)
                .collect(Collectors.toList());
    }

    // ── 合成层 ───────────────────────────────────────────────────────────

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
            String domainName = str(fm, "domain_name");
            String subDomainName = str(fm, "sub_domain_name");
            String domainDescription = str(fm, "domain_description");
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

            // 解析 tool_flow：新格式为字符串列表，向后兼容 Map 格式（只取 toolCode）
            List<String> tools = new ArrayList<>();
            if (fm.containsKey("tool_flow")) {
                Object rawFlow = fm.get("tool_flow");
                if (rawFlow instanceof List<?> flowList) {
                    for (Object item : flowList) {
                        if (item instanceof String s) {
                            tools.add(s);
                        } else if (item instanceof Map<?, ?> stepMap) {
                            Object tc = stepMap.get("toolCode");
                            if (tc != null) tools.add(tc.toString());
                        }
                    }
                }
            }

            SkillMetadata meta = new SkillMetadata(name, description, domain, subDomain,
                    domainName, subDomainName, domainDescription,
                    "*".equals(domain), requiresKnowledge, activationRule, tools, body);

            if ("*".equals(domain)) {
                crossDomainMeta = meta;
            } else if (subDomain != null && !subDomain.isBlank()) {
                metaByKey.put(domain + "|" + subDomain, meta);
            } else {
                metaByKey.put(domain, meta);
            }
            log.debug("SkillRegistry: 注册 skill={}, domain={}, subDomain={}, tools={}个",
                    name, domain, subDomain, tools.size());
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
