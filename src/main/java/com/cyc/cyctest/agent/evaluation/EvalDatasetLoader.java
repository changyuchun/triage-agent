package com.cyc.cyctest.agent.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 从 classpath:eval/cases.yaml 加载评估黄金集。
 * <p>
 * Spring Boot 自带 snakeyaml，无需额外依赖。
 * YAML 解析到 Map 后手动映射，避免引入 jackson-dataformat-yaml。
 */
@Component
public class EvalDatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetLoader.class);
    private static final String DATASET_PATH = "eval/cases.yaml";

    @SuppressWarnings("unchecked")
    public List<EvalCase> load() {
        try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            List<Map<String, Object>> rawCases = (List<Map<String, Object>>) root.get("cases");
            if (rawCases == null) {
                log.warn("EvalDatasetLoader: cases.yaml 中未找到 cases 列表");
                return List.of();
            }
            List<EvalCase> cases = rawCases.stream()
                    .map(this::mapCase)
                    .filter(Objects::nonNull)
                    .toList();
            log.info("EvalDatasetLoader: 加载 {} 个评估用例", cases.size());
            return cases;
        } catch (IOException e) {
            log.warn("EvalDatasetLoader: 无法加载 {}: {}", DATASET_PATH, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private EvalCase mapCase(Map<String, Object> raw) {
        try {
            String caseId = (String) raw.get("caseId");
            String userText = (String) raw.get("userText");
            if (caseId == null || userText == null) return null;

            Map<String, String> presetSlots = (Map<String, String>) raw.getOrDefault("presetSlots", Map.of());
            List<String> tags = (List<String>) raw.getOrDefault("tags", List.of());

            Map<String, Object> exp = (Map<String, Object>) raw.getOrDefault("expected", Map.of());
            String domainCode = (String) exp.get("domainCode");
            List<String> toolCodes = (List<String>) exp.getOrDefault("toolCodes", List.of());
            double minQuality = ((Number) exp.getOrDefault("minQualityScore", 0.3)).doubleValue();
            List<String> mustContainFacts = (List<String>) exp.getOrDefault("mustContainFacts", List.of());

            EvalCase.Expected expected = new EvalCase.Expected(domainCode, toolCodes, minQuality, mustContainFacts);
            return new EvalCase(caseId, userText, presetSlots, expected, tags);
        } catch (Exception e) {
            log.warn("EvalDatasetLoader: 跳过无效用例: {}", e.getMessage());
            return null;
        }
    }
}
