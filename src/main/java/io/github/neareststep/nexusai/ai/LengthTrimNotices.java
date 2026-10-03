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
 * per player. The line prints that name and its length. It does not print placeholder
 * template text. The map holds at most {@value #MAX_KEYS} keys.
 * {@link #reset()} runs on {@code /nai reload}.
 */
public final class LengthTrimNotices {

    static final int PER_PROMPT = 2;
    /**
     * Config ids stay well under this. The cap only matters when a caller still passes
     * unbounded literal text. The map is an access-order LRU: a new id drops the least
     * recently used one once this many keys are stored.
     */
    static final int MAX_KEYS = 256;
    /** A short prompt id is logged by name. Longer text is treated as template content. */
    private static final int NAME_LIMIT = 64;
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
        String key = mapKey(id);
        int seen;
        synchronized (COUNTS) {
            seen = COUNTS.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        }
        if (seen > PER_PROMPT) {
            return;
        }
        logger.info(message(id));
    }

    /**
     * The line names the prompt id when that id is a short name, and always includes a length
     * for a name. Template text (markup, braces, or anything longer than {@value #NAME_LIMIT}
     * characters) is not written; only its length is.
     */
    public static String message(String id) {
        String clean = cleaned(id);
        String shown;
        if (clean.isEmpty()) {
            shown = "(blank)";
        } else if (isName(clean)) {
            shown = clean + " (length " + clean.length() + ")";
        } else {
            shown = "(length " + clean.length() + ")";
        }
        return "reply for prompt " + shown
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

    /**
     * Prompt name after colour codes are removed, {@code (blank)} when nothing remains,
     * or {@code (length N)} when the id is template content rather than a name.
     */
    static String label(String id) {
        String clean = cleaned(id);
        if (clean.isEmpty()) {
            return "(blank)";
        }
        if (isName(clean)) {
            return clean;
        }
        return "(length " + clean.length() + ")";
    }

    /**
     * Suppression key. Colour-code variants of one name share a key. Template content is
     * not stored; a length and a hash keep two different templates apart without logging them.
     */
    private static String mapKey(String id) {
        String clean = cleaned(id);
        if (clean.isEmpty()) {
            return "(blank)";
        }
        if (isName(clean)) {
            return clean;
        }
        return "len:" + clean.length() + ":" + Integer.toHexString(clean.hashCode());
    }

    private static String cleaned(String id) {
        if (id == null) {
            return "";
        }
        return PlayerInput.sanitize(id).replace("&", "").replace("§", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private static boolean isName(String clean) {
        if (clean.length() > NAME_LIMIT) {
            return false;
        }
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '<' || c == '>' || c == '{' || c == '}' || c == '#') {
                return false;
            }
        }
        return true;
    }
}
