package com.cyc.cyctest.agent.graph.react.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双层 Skill 注册表：实现 SAA SkillRegistry 接口，桥接项目自研 SkillRegistry（classpath）
 * 和 FileSystemSkillRegistry（./skills/，用户控制台新增）。
 * FileSystem 层优先级高于 classpath 层。
 */
@Component
public class CompositeSkillRegistry implements com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompositeSkillRegistry.class);

    private static final String PROMPT_TEMPLATE = """
            ## 可用技能（Skills）

            以下是当前已注册的排查技能，使用前必须先调用 `read_skill` 工具加载完整内容。

            {skills_list}

            **加载说明**：{skills_load_instructions}
            """;

    private final com.cyc.cyctest.agent.skill.SkillRegistry projectRegistry;
    private final FileSystemSkillRegistry fsRegistry;
    private final Map<String, SkillMetadata> fsCache = new ConcurrentHashMap<>();

    public CompositeSkillRegistry(
            com.cyc.cyctest.agent.skill.SkillRegistry projectRegistry,
            @Value("${agent.skills.filesystem.dir:./skills}") String fsDir) {
        this.projectRegistry = projectRegistry;
        this.fsRegistry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(fsDir)
                .autoLoad(true)
                .build();
    }

    @PostConstruct
    public void init() {
        refreshFsCache();
        log.info("CompositeSkillRegistry 就绪：内置={} 个，文件系统={} 个",
                projectRegistry.allSkills().size(), fsCache.size());
    }

    // ── SAA SkillRegistry 接口 ─────────────────────────────────────────

    @Override
    public Optional<SkillMetadata> get(String name) {
        SkillMetadata fs = fsCache.get(name);
        if (fs != null) return Optional.of(fs);
        return projectRegistry.findByName(name).map(this::bridge);
    }

    @Override
    public List<SkillMetadata> listAll() {
        Map<String, SkillMetadata> merged = new LinkedHashMap<>();
        projectRegistry.allSkills().forEach(m -> merged.put(m.name(), bridge(m)));
        merged.putAll(fsCache);                  // filesystem 覆盖同名
        return List.copyOf(merged.values());
    }

    @Override
    public boolean contains(String name) {
        return fsCache.containsKey(name) || projectRegistry.findByName(name).isPresent();
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        if (fsCache.containsKey(name)) {
            return fsCache.get(name).loadFullContent();
        }
        return projectRegistry.findByName(name)
                .map(m -> {
                    StringBuilder sb = new StringBuilder();
                    if (!m.tools().isEmpty()) {
                        sb.append("## 可用工具\n");
                        m.tools().forEach(t -> sb.append("- ").append(t).append("\n"));
                        sb.append("\n");
                    }
                    if (m.sopContent() != null) sb.append("## SOP\n").append(m.sopContent());
                    return sb.toString();
                })
                .orElseThrow(() -> new IllegalStateException("Skill 未找到: " + name));
    }

    @Override
    public void reload() {
        try { fsRegistry.reload(); } catch (Exception ignored) {}
        refreshFsCache();
        log.info("CompositeSkillRegistry 热重载：文件系统={} 个", fsCache.size());
    }

    @Override
    public int size() {
        Set<String> names = new HashSet<>();
        projectRegistry.allSkills().forEach(m -> names.add(m.name()));
        names.addAll(fsCache.keySet());
        return names.size();
    }

    @Override
    public String getSkillLoadInstructions() {
        return "调用 `read_skill(skillName)` 工具加载技能的完整 tool_flow 和 SOP 内容。";
    }

    @Override
    public String getRegistryType() {
        return "Composite";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return SystemPromptTemplate.builder().template(PROMPT_TEMPLATE).build();
    }

    // ── 控制台写入 API ────────────────────────────────────────────────────

    public void writeSkill(String name, String content) throws IOException {
        String dir = fsDir();
        Path skillDir = Path.of(dir, name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content);
        reload();
    }

    public void deleteSkill(String name) throws IOException {
        Path skillFile = Path.of(fsDir(), name, "SKILL.md");
        Files.deleteIfExists(skillFile);
        Path dir = skillFile.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                if (s.findFirst().isEmpty()) Files.delete(dir);
            }
        }
        reload();
    }

    public boolean isFsSkill(String name) {
        return fsCache.containsKey(name);
    }

    /** 以 SkillEntry 形式列出所有（含 source 标识），供 Admin API 使用。 */
    public List<SkillEntry> listEntries() {
        Map<String, SkillEntry> merged = new LinkedHashMap<>();
        projectRegistry.allSkills().forEach(m ->
                merged.put(m.name(), new SkillEntry(m.name(), m.description(),
                        m.domain(), m.subDomain(), "classpath")));
        fsCache.forEach((k, v) ->
                merged.put(k, new SkillEntry(v.getName(), v.getDescription(), null, null, "filesystem")));
        return List.copyOf(merged.values());
    }

    // ── 内部辅助 ──────────────────────────────────────────────────────────

    private void refreshFsCache() {
        fsCache.clear();
        fsRegistry.listAll().forEach(m -> fsCache.put(m.getName(), m));
    }

    private SkillMetadata bridge(com.cyc.cyctest.agent.skill.SkillMetadata m) {
        return SkillMetadata.builder()
                .name(m.name())
                .description(m.description())
                .skillPath("classpath:skills/" + m.name())
                .source("project")
                .build();
    }

    private String fsDir() {
        String d = fsRegistry.getProjectSkillsDirectory();
        return d != null ? d : "./skills";
    }

    public record SkillEntry(String name, String description, String domain, String subDomain, String source) {}
}
