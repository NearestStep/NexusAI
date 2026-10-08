package io.github.neareststep.nexusai.json;

import java.nio.charset.StandardCharsets;

/**
 * Pulls one JSON object out of a model reply. A markdown fence is removed first.
 * A longer reply is not parsed.
 */
public final class JsonExtractor {

    /** UTF-8 bytes. A longer reply is refused. */
    public static final int MAX_BYTES = 64 * 1024;

    private JsonExtractor() {
    }

    /**
     * The first balanced {@code {...}} object, after a leading markdown fence is removed.
     * Empty when the reply is too long or no object is closed.
     */
    public static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return null;
        }
        String text = stripFence(raw.trim());
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** True when {@code raw} is longer than {@link #MAX_BYTES}. */
    public static boolean tooLarge(String raw) {
        return raw != null && raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /**
     * Same fence rule as moderation verdicts: a reply that starts with {@code ```} loses
     * the opening line and the closing fence. Any other reply is unchanged.
     */
    static String stripFence(String text) {
        if (text == null || !text.startsWith("```")) {
            return text == null ? "" : text;
        }
        int newline = text.indexOf('\n');
        String body = newline >= 0 ? text.substring(newline + 1) : text.substring(3);
        int fence = body.lastIndexOf("```");
        if (fence >= 0) {
            body = body.substring(0, fence);
        }
        return body.trim();
    }
}
