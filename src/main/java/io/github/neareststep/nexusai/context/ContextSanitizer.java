package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.ai.PlayerInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns provider text into one untrusted line, then one wrapped block.
 * Markup is always removed, including when {@code sanitize.allow-markup} is true.
 * Provider values are never written to the log.
 */
public final class ContextSanitizer {

    private ContextSanitizer() {
    }

    public record Line(String id, int priority, String text) {
    }

    /**
     * @param truncated notified with the provider id the first time this value is cut, when non-null
     * @return one line of text, or empty when the provider had nothing to say
     */
    public static String value(String raw, int maxChars, Consumer<String> truncated, String providerId) {
        String flat = flatten(PlayerInput.stripSectionSigns(raw, false));
        if (flat.isEmpty()) {
            return "";
        }
        int points = flat.codePointCount(0, flat.length());
        if (points <= maxChars) {
            return flat;
        }
        if (truncated != null && providerId != null) {
            truncated.accept(providerId);
        }
        int end = flat.offsetByCodePoints(0, Math.max(0, maxChars));
        return flat.substring(0, end) + "…";
    }

    /**
     * Keeps lines from the front (higher priority) until {@code maxChars} code points.
     * A line that does not fit is dropped whole, and so is every line after it.
     * The kept text is wrapped. An empty result means the prompt stays unchanged.
     */
    public static String block(List<Line> lines, int maxChars) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> rows = new ArrayList<>();
        for (Line line : lines) {
            if (line == null || line.text() == null || line.text().isEmpty() || line.id() == null || line.id().isEmpty()) {
                continue;
            }
            rows.add(line.id() + ": " + line.text());
        }
        while (!rows.isEmpty() && codePoints(String.join("\n", rows)) > maxChars) {
            rows.remove(rows.size() - 1);
        }
        if (rows.isEmpty()) {
            return "";
        }
        return PlayerInput.wrap(String.join("\n", rows));
    }

    static String flatten(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) || cp == '\u2028' || cp == '\u2029') {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && !out.isEmpty()) {
                out.append(' ');
            }
            pendingSpace = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }
}
