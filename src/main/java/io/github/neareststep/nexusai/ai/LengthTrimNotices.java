package io.github.neareststep.nexusai.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * INFO when a reply was cut off by {@code max_tokens}. Each distinct prompt id is logged
 * twice, then suppressed, so a placeholder that always hits the cap does not write a line
 * on every refresh. The key is a stable id (prompt id, placeholder name, pool name, or
 * talk persona), never the rendered prompt, so {@code {player}} does not mint a new key
 * per player. {@link #reset()} runs on {@code /nai reload}.
 */
public final class LengthTrimNotices {

    static final int PER_PROMPT = 2;
    /**
     * Config ids stay well under this. The cap only matters when a caller still passes
     * unbounded literal text.
     */
    static final int MAX_KEYS = 256;
    private static final int LABEL_LIMIT = 120;
    private static final Map<String, AtomicInteger> COUNTS = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > MAX_KEYS;
                }
            });

    private LengthTrimNotices() {
    }

    public static void note(Logger logger, String id) {
        if (logger == null) {
            return;
        }
        String key = label(id);
        int seen;
        synchronized (COUNTS) {
            seen = COUNTS.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        }
        if (seen > PER_PROMPT) {
            return;
        }
        logger.info(message(key));
    }

    public static String message(String id) {
        return "reply for prompt " + label(id)
                + " hit max-tokens and was trimmed; increase max-tokens for this prompt";
    }

    /**
     * Clears the per-id counters. {@code /nai reload} calls this so a changed prompt can log again.
     */
    public static void reset() {
        synchronized (COUNTS) {
            COUNTS.clear();
        }
    }

    static int tracked() {
        synchronized (COUNTS) {
            return COUNTS.size();
        }
    }

    static String label(String id) {
        if (id == null) {
            return "(blank)";
        }
        String clean = PlayerInput.stripSectionSigns(id).replace("&", "").replace("§", "");
        clean = clean.replace('\r', ' ').replace('\n', ' ').trim();
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
