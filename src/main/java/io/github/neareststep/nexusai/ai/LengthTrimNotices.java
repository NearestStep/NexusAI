package io.github.neareststep.nexusai.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * INFO when a reply was cut off by {@code max_tokens}. Each distinct prompt id is logged
 * twice, then suppressed, so a placeholder that always hits the cap does not write a line
 * on every refresh. The key is a stable id (prompt id, placeholder name, pool name, or
 * talk persona), never the rendered prompt, so {@code {player}} does not mint a new key
 * per player. The line prints a named id ({@code [a-z0-9_-]+} or {@code nai talk}) and
 * its length. Literal placeholder text is not printed. The map holds at most
 * {@value #MAX_KEYS} keys.
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
    /** A prompt id is logged by name. Anything else, including a short literal, is not. */
    private static final int NAME_LIMIT = 64;
    /** Same shape as a {@code prompts.yml} id. Upper case and other punctuation are literals. */
    private static final Pattern NAMED_ID = Pattern.compile("[a-z0-9_-]+");
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
     * The line names the prompt id when that id is a {@code prompts.yml} id or {@code nai talk}.
     * A literal placeholder, including a short one such as {@code short_LT:long}, is not written;
     * only its length is.
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
        if ("nai talk".equals(clean)) {
            return true;
        }
        return clean.length() <= NAME_LIMIT && NAMED_ID.matcher(clean).matches();
    }
}
