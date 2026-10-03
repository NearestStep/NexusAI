package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.PlayerInput;

/**
 * Cleans a summary the model returned. The text is untrusted: it is built from player lines.
 */
public final class SummaryText {

    private SummaryText() {
    }

    /**
     * @return the stored summary, or empty when the reply is refused
     */
    public static String clean(String raw, String prompt, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (PlayerInput.rejectionReason(raw, prompt) != null) {
            return "";
        }
        String stripped = PlayerInput.stripSectionSigns(raw, false);
        if (stripped.isBlank() || PlayerInput.rejectionReason(stripped, prompt) != null) {
            return "";
        }
        String flat = collapse(stripped);
        if (flat.isEmpty()) {
            return "";
        }
        if (flat.length() > maxChars) {
            flat = LengthCutoff.limit(flat, maxChars);
        }
        flat = flat.strip();
        if (flat.isEmpty() || "…".equals(flat)) {
            return "";
        }
        return flat;
    }

    private static String collapse(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                space = true;
                continue;
            }
            if (space && !out.isEmpty()) {
                out.append(' ');
            }
            space = false;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
