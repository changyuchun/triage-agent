package com.cyc.cyctest.agent.slot;

import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class SlotExtractionService {
    private final List<SlotExtractor> extractors;

    public SlotExtractionService(List<SlotExtractor> extractors) {
        this.extractors = extractors.stream()
                .sorted(Comparator.comparingInt(SlotExtractor::priority).reversed())
                .toList();
    }

    public SlotState extractAndMerge(String userText, ConversationContext context) {
        SlotState current = context.slotState();
        for (SlotExtractor extractor : extractors) {
            current = current.merge(extractor.extract(userText, context));
        }
        context.mergeSlots(current);
        return current;
    }
}
