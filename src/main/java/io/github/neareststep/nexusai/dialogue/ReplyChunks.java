package io.github.neareststep.nexusai.dialogue;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a finished reply into chat-sized pieces. Pure string work; no Bukkit.
 * Prefer a break at a space, newline, or sentence end so chunks stay readable.
 */
public final class ReplyChunks {

    private ReplyChunks() {
    }

    public static List<String> split(String text, int charsPerChunk) {
        String source = text == null ? "" : text;
        int limit = Math.max(1, charsPerChunk);
        if (source.isEmpty()) {
            return List.of();
        }
        if (source.length() <= limit) {
            return List.of(source);
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < source.length()) {
            int end = Math.min(source.length(), start + limit);
            if (end < source.length()) {
                int breakAt = lastBreak(source, start, end);
                if (breakAt > start) {
                    end = breakAt;
                }
            }
            String piece = source.substring(start, end).stripLeading();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            start = end;
            while (start < source.length() && Character.isWhitespace(source.charAt(start))) {
                start++;
            }
        }
        return chunks.isEmpty() ? List.of(source) : List.copyOf(chunks);
    }

    private static int lastBreak(String text, int start, int end) {
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == ' ' || c == '\n' || c == '\t' || c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':') {
                return i;
            }
        }
        return end;
    }
}
