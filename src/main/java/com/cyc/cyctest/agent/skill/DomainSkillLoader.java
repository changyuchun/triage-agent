package com.cyc.cyctest.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从 classpath:skills 目录加载 SKILL.md 文件，提供领域 SOP 内容。
 * <p>
 * SKILL.md 格式：YAML frontmatter（含 domain 字段）+ Markdown 正文（SOP）。
 * domain=* 表示跨领域 Skill（如 log-query），会附加到所有领域的 SOP 末尾。
 */
@Component
public class DomainSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(DomainSkillLoader.class);
    private static final String FRONTMATTER_DELIMITER = "---";

    private final Map<String, String> sopByDomain = new LinkedHashMap<>();
    private String crossDomainSop = "";

    @PostConstruct
    public void load() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*/SKILL.md");
            for (Resource resource : resources) {
                parseAndRegister(resource);
            }
            log.info("DomainSkillLoader: 加载 {} 个 SKILL.md，跨领域 SOP: {}",
                    sopByDomain.size(), crossDomainSop.isBlank() ? "无" : "已加载");
        } catch (IOException e) {
            log.warn("DomainSkillLoader: SKILL.md 加载失败，SOP 注入不可用: {}", e.getMessage());
        }
    }

    /**
     * 返回指定领域的 SOP 内容（SKILL.md 正文），附加跨领域 SOP。
     * 若无对应 SKILL.md，返回空字符串。
     */
    public String sopFor(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            return crossDomainSop;
        }
        String domainSop = sopByDomain.getOrDefault(domainCode, "");
        if (crossDomainSop.isBlank()) {
            return domainSop;
        }
        return domainSop.isBlank()
                ? crossDomainSop
                : domainSop + "\n\n---\n\n" + crossDomainSop;
    }

    private void parseAndRegister(Resource resource) {
        try {
            String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String domain = extractFrontmatterField(raw, "domain");
            String body = extractBody(raw);
            if (domain == null || body.isBlank()) {
                log.debug("DomainSkillLoader: 跳过 {} (缺少 domain 或正文)", resource.getFilename());
                return;
            }
            if ("*".equals(domain)) {
                crossDomainSop = body;
            } else {
                sopByDomain.put(domain, body);
            }
            log.debug("DomainSkillLoader: 已注册 domain={}, 正文长度={}", domain, body.length());
        } catch (IOException e) {
            log.warn("DomainSkillLoader: 读取 {} 失败: {}", resource.getFilename(), e.getMessage());
        }
    }

    private String extractFrontmatterField(String content, String field) {
        int first = content.indexOf(FRONTMATTER_DELIMITER);
        if (first < 0) return null;
        int second = content.indexOf(FRONTMATTER_DELIMITER, first + 3);
        if (second < 0) return null;
        String frontmatter = content.substring(first + 3, second);
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(field + ":")) {
                return trimmed.substring(field.length() + 1).trim();
            }
        }
        return null;
    }

    private String extractBody(String content) {
        int first = content.indexOf(FRONTMATTER_DELIMITER);
        if (first < 0) return content.trim();
        int second = content.indexOf(FRONTMATTER_DELIMITER, first + 3);
        if (second < 0) return content.trim();
        return content.substring(second + 3).trim();
    }
}
