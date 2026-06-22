package com.cyc.cyctest.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public <T> T readJsonObject(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(extractJsonObject(raw), type);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid json object: " + raw, e);
        }
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("json serialize failed", e);
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }
}
