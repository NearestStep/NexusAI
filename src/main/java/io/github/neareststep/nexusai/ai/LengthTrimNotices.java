package io.github.neareststep.nexusai.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * INFO when a reply was cut off by {@code max_tokens}. Each distinct prompt is logged
 * twice per process, then suppressed, so a placeholder that always hits the cap does not
 * write a line on every refresh.
 */
public final class LengthTrimNotices {

    static final int PER_PROMPT = 2;
    private static final int LABEL_LIMIT = 120;
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

    private LengthTrimNotices() {
    }

    public static void note(Logger logger, String prompt) {
        if (logger == null) {
            return;
        }
        String label = label(prompt);
        int seen = COUNTS.computeIfAbsent(label, ignored -> new AtomicInteger()).incrementAndGet();
        if (seen > PER_PROMPT) {
            return;
        }
        logger.info(message(label));
    }

    public static String message(String prompt) {
        return "reply for prompt " + prompt
                + " hit max-tokens and was trimmed; increase max-tokens for this prompt";
    }

    static void reset() {
        COUNTS.clear();
    }

    static String label(String prompt) {
        if (prompt == null) {
            return "(blank)";
        }
        String clean = prompt.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.isEmpty()) {
            return "(blank)";
        }
        if (clean.length() <= LABEL_LIMIT) {
            return clean;
        }
        int end = LABEL_LIMIT - 3;
        if (Character.isLowSurrogate(clean.charAt(end))) {
            end--;
        }
        return clean.substring(0, end) + "...";
    }
}
