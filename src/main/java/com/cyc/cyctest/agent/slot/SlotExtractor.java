package com.cyc.cyctest.agent.slot;

import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;

import java.util.Map;
import java.util.Set;

public interface SlotExtractor {
    int priority();

    Set<String> slotNames();

    SlotState extract(String userText, ConversationContext context);

    /** 当本 Extractor 关注的字段缺失时，应该问用户什么。key = 缺失类别（objectId/env） */
    default Map<String, String> clarifyPrompts() { return Map.of(); }
}
