package com.cyc.cyctest.agent.slot;

import com.cyc.cyctest.agent.core.AgentModels.Env;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarketingSlotExtractor implements SlotExtractor {
    private static final Pattern ACTIVITY = Pattern.compile("\\b(ACT|ACTIVITY)[-_]?[0-9A-Za-z]{4,32}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUPON = Pattern.compile("\\b(CPN|COUPON)[-_]?[0-9A-Za-z]{4,32}\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public int priority() {
        return 80;
    }

    @Override
    public Set<String> slotNames() {
        return Set.of("activityId", "couponId");
    }

    @Override
    public SlotState extract(String userText, ConversationContext context) {
        String text = userText == null ? "" : userText;
        return new SlotState(null, null, null, Env.UNKNOWN, null, null, findFirst(ACTIVITY, text), findFirst(COUPON, text));
    }

    private static String findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
