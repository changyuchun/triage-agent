package com.cyc.cyctest.agent.domain;

import com.cyc.cyctest.agent.domain.DomainModels.DomainCandidate;
import com.cyc.cyctest.agent.domain.DomainModels.SubDomainCandidate;
import com.cyc.cyctest.agent.skill.SkillMetadata;
import com.cyc.cyctest.agent.skill.SkillRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 SkillRegistry 动态聚合领域候选列表。
 * SKILL.md 是单一信息源：新增/删除 SKILL.md 文件即可改变可路由的域。
 */
@Component
public class DomainRegistry {

    private final SkillRegistry skillRegistry;

    public DomainRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public List<DomainCandidate> enabledDomains() {
        Map<String, List<SkillMetadata>> byDomain = new LinkedHashMap<>();
        for (SkillMetadata meta : skillRegistry.allSkills()) {
            if (meta.crossDomain() || meta.domain() == null) continue;
            if (meta.subDomain() == null || meta.subDomain().isBlank()) continue;
            byDomain.computeIfAbsent(meta.domain(), k -> new ArrayList<>()).add(meta);
        }

        List<DomainCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<SkillMetadata>> entry : byDomain.entrySet()) {
            String domainCode = entry.getKey();
            List<SkillMetadata> skills = entry.getValue();
            SkillMetadata first = skills.get(0);

            List<SubDomainCandidate> subDomains = skills.stream()
                    .map(m -> new SubDomainCandidate(
                            m.subDomain(),
                            m.subDomainName() != null ? m.subDomainName() : m.subDomain(),
                            m.description()))
                    .toList();

            result.add(new DomainCandidate(
                    domainCode,
                    first.domainName() != null ? first.domainName() : domainCode,
                    first.domainDescription() != null ? first.domainDescription() : "",
                    subDomains));
        }
        return result;
    }

    public boolean contains(String domainCode, String subDomainCode) {
        return enabledDomains().stream()
                .filter(d -> d.domainCode().equals(domainCode))
                .flatMap(d -> d.subDomains().stream())
                .anyMatch(s -> s.subDomainCode().equals(subDomainCode));
    }

    public DomainCandidate findDomain(String domainCode) {
        return enabledDomains().stream()
                .filter(d -> d.domainCode().equals(domainCode))
                .findFirst()
                .orElse(null);
    }
}
