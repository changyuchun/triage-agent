package com.cyc.cyctest.agent.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 安全护栏服务（Guardrails）- 生产级 Agent 安全层。
 * <p>
 * 覆盖三大安全威胁：
 * 1. Prompt Injection：检测试图覆盖系统指令的恶意输入
 * 2. PII Masking：手机号/身份证/银行卡脱敏后再发给 LLM
 * 3. Rate Limiting：滑动窗口令牌桶，防止滥用和成本失控
 * <p>
 * 类比 Spring Security 的 FilterChain：每个请求进入 Agent 前都要过此安全网。
 */
@Service
public class GuardrailsService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailsService.class);

    // Prompt Injection 特征模式（参考 OWASP LLM Top 10 - LLM01）
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|rules?|prompts?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(everything|all|your|the)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you are now\\s+(?!an? (assistant|agent|AI))", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all|your|the|previous)\\s+(instructions?|rules?|system)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("</?(system|instruction|prompt|context)>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[\\s*SYSTEM\\s*\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("###\\s*(System|Instruction|Override)", Pattern.CASE_INSENSITIVE)
    );

    // PII 正则：中国手机号 / 身份证 / 银行卡
    private static final Pattern PII_PHONE = Pattern.compile("(?<![\\d])(1[3-9]\\d{9})(?![\\d])");
    private static final Pattern PII_ID_CARD = Pattern.compile("\\b(\\d{6})(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b");
    private static final Pattern PII_BANK_CARD = Pattern.compile("(?<![\\d])(\\d{16,19})(?![\\d])");

    // 令牌桶：per-session，每 60 秒最多 30 次请求
    private final Map<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();

    private static final int MAX_INPUT_LENGTH = 8000;
    private static final int RATE_LIMIT_PER_MINUTE = 30;
    private static final long RATE_WINDOW_MS = 60_000L;

    /**
     * 对输入进行安全检查。
     *
     * @return GuardrailsResult.blocked=true 时应直接拒绝请求；blocked=false 时 sanitizedInput 是脱敏后的安全输入
     */
    public GuardrailsResult check(String sessionId, String input) {
        if (input == null || input.isBlank()) {
            return GuardrailsResult.block("输入不能为空");
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            return GuardrailsResult.block("输入超过最大长度限制（" + MAX_INPUT_LENGTH + " 字符）");
        }

        // 1. Prompt Injection 检测
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(input).find()) {
                log.warn("[Guardrails] Prompt Injection 检测 session={} pattern={}", sessionId, p.pattern());
                return GuardrailsResult.block("检测到非法指令注入，请求被安全策略拒绝");
            }
        }

        // 2. Rate Limiting - 滑动窗口令牌桶
        RateBucket bucket = rateBuckets.computeIfAbsent(sessionId, k -> new RateBucket(RATE_LIMIT_PER_MINUTE, RATE_WINDOW_MS));
        if (!bucket.tryAcquire()) {
            log.warn("[Guardrails] Rate limit exceeded session={}", sessionId);
            return GuardrailsResult.block("请求过于频繁，请稍后再试（每分钟最多 " + RATE_LIMIT_PER_MINUTE + " 次）");
        }

        // 3. PII Masking - 脱敏后才发给 LLM
        String sanitized = maskPii(input);
        if (!sanitized.equals(input)) {
            log.info("[Guardrails] PII masking applied session={}", sessionId);
        }

        return GuardrailsResult.pass(sanitized);
    }

    private String maskPii(String text) {
        // 手机号：保留前3后2
        text = PII_PHONE.matcher(text).replaceAll(m -> {
            String s = m.group(1);
            return s.substring(0, 3) + "****" + s.substring(7);
        });
        // 身份证：保留前6后4
        text = PII_ID_CARD.matcher(text).replaceAll(m -> {
            String s = m.group(0);
            return s.substring(0, 6) + "********" + s.substring(s.length() - 4);
        });
        // 银行卡：保留前4后4
        text = PII_BANK_CARD.matcher(text).replaceAll(m -> {
            String s = m.group(1);
            if (s.length() < 12) return s;
            return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
        });
        return text;
    }

    // ---- 结果 VO ----

    public record GuardrailsResult(boolean blocked, String reason, String sanitizedInput) {
        public static GuardrailsResult block(String reason) {
            return new GuardrailsResult(true, reason, null);
        }

        public static GuardrailsResult pass(String sanitized) {
            return new GuardrailsResult(false, null, sanitized);
        }
    }

    // ---- 滑动窗口令牌桶（轻量实现，无需 Bucket4j 外部依赖）----

    static class RateBucket {
        private final int maxRequests;
        private final long windowMs;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        RateBucket(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= maxRequests;
        }
    }
}
