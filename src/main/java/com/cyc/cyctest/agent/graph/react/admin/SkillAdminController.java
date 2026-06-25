package com.cyc.cyctest.agent.graph.react.admin;

import com.cyc.cyctest.agent.graph.react.skill.CompositeSkillRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Skill 管理控制台 REST API。
 * 仅允许操作 filesystem 层 Skill，classpath 内置 Skill 只读。
 */
@RestController
@RequestMapping("/admin/skills")
public class SkillAdminController {

    private final CompositeSkillRegistry registry;

    public SkillAdminController(CompositeSkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<CompositeSkillRegistry.SkillEntry> list() {
        return registry.listEntries();
    }

    @GetMapping("/{name}")
    public ResponseEntity<String> get(@PathVariable String name) {
        try {
            return ResponseEntity.ok(registry.readSkillContent(name));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create(@RequestBody SkillRequest req) {
        if (req.name() == null || req.name().isBlank() || req.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 和 content 不能为空"));
        }
        if (!req.name().matches("[a-z0-9-]{1,64}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 格式错误（小写字母+数字+连字符，最长64字符）"));
        }
        try {
            registry.writeSkill(req.name(), req.content());
            return ResponseEntity.ok(Map.of("message", "Skill 已创建：" + req.name()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<Map<String, String>> update(@PathVariable String name,
                                                       @RequestBody SkillRequest req) {
        if (req.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "content 不能为空"));
        }
        try {
            registry.writeSkill(name, req.content());
            return ResponseEntity.ok(Map.of("message", "Skill 已更新：" + name));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String name) {
        if (!registry.isFsSkill(name)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "内置 classpath Skill 不允许删除"));
        }
        try {
            registry.deleteSkill(name);
            return ResponseEntity.ok(Map.of("message", "Skill 已删除：" + name));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    public record SkillRequest(String name, String content) {}
}
