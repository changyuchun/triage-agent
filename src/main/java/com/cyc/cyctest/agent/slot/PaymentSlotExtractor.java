package com.cyc.cyctest.agent.slot;

import com.cyc.cyctest.agent.core.AgentModels.Env;
import com.cyc.cyctest.agent.core.AgentModels.SlotState;
import com.cyc.cyctest.agent.memory.ConversationContext;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PaymentSlotExtractor implements SlotExtractor {
    private static final Pattern PAY_ORDER = Pattern.compile("\\b(PAY|P)[-_]?[0-9A-Za-z]{8,32}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER = Pattern.compile("\\b(ORD|ORDER|T)[-_]?[0-9A-Za-z]{8,32}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHECKOUT = Pattern.compile("\\b(CK|CHECKOUT)[-_]?[0-9A-Za-z]{6,32}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z][A-Z0-9_]{2,40}\\b");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Set<String> slotNames() {
        return Set.of("payOrderId", "orderId", "checkoutId", "env", "errorCode", "timeRange");
    }

    @Override
    public SlotState extract(String userText, ConversationContext context) {
        String text = userText == null ? "" : userText;
        return new SlotState(
                findFirst(ORDER, text),
                findFirst(PAY_ORDER, text),
                findFirst(CHECKOUT, text),
                parseEnv(text),
                parseErrorCode(text),
                parseTimeRange(text),
                null,
                null
        );
    }

    private String parseErrorCode(String text) {
        Matcher matcher = ERROR_CODE.matcher(text);
        while (matcher.find()) {
            String code = matcher.group();
            if (!code.equalsIgnoreCase("PROD") && !code.equalsIgnoreCase("PRE") && !code.equalsIgnoreCase("DAILY")) {
                return code;
            }
        }
        return null;
    }

    private static String findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static Env parseEnv(String text) {
        String t = text.toLowerCase();
        if (t.contains("生产") || t.contains("线上") || t.contains("prod")) {
            return Env.PROD;
        }
        if (t.contains("预发") || t.contains("pre")) {
            return Env.PRE;
        }
        if (t.contains("日常") || t.contains("daily") || t.contains("test")) {
            return Env.DAILY;
        }
        return Env.UNKNOWN;
    }

    private static String parseTimeRange(String text) {
        if (text.contains("今天")) {
            return "TODAY";
        }
        if (text.contains("昨天")) {
            return "YESTERDAY";
        }
        if (text.contains("最近一小时")) {
            return "LAST_1H";
        }
        if (text.contains("最近半小时")) {
            return "LAST_30M";
        }
        return null;
    }
}
