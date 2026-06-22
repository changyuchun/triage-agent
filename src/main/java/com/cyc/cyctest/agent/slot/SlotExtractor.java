package com.cyc.cyctest.agent.slot;

import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;

import java.util.Set;

public interface SlotExtractor {
    int priority();

    Set<String> slotNames();

    SlotState extract(String userText, ConversationContext context);
}
