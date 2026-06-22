package com.cyc.cyctest.agent.skill;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Skill 注册表（代码 Skill，区别于 Spring AI Alibaba 的文件系统 SkillRegistry）。
 * <p>
 * 所有 @Component 的 AgentSkill 实现类自动注入，按 skillId 索引。
 * TaskPlanner 在生成计划时可通过此注册表查询可用 Skill。
 */
@Component
public class AgentSkillRegistry {

    private final Map<String, AgentSkill> skills;

    public AgentSkillRegistry(List<AgentSkill> skillList) {
        this.skills = skillList.stream()
                .filter(AgentSkill::enabled)
                .collect(Collectors.toMap(
                        AgentSkill::skillId,
                        s -> s,
                        (a, b) -> a,
                        LinkedHashMap::new));
    }

    public Optional<AgentSkill> findById(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    public List<AgentSkill> listAll() {
        return Collections.unmodifiableList(new ArrayList<>(skills.values()));
    }

    public List<AgentSkill> findByCategory(String category) {
        return skills.values().stream()
                .filter(s -> s.category().equals(category))
                .collect(Collectors.toList());
    }

    public int size() {
        return skills.size();
    }
}
