package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.ai.LengthCutoff;

import java.util.List;

/**
 * The one correction retry for a JSON reply that did not parse or did not match the schema.
 * A provider error does not use this retry. A {@code finish_reason} of {@code length} doubles
 * {@code max_tokens} and does not send the cut reply back.
 */
public final class JsonRepair {

    public static final int ASSISTANT_LIMIT = 4000;
    public static final int ERRORS_IN_PROMPT = 5;
    public static final int MAX_TOKENS_CAP = 4096;

    static final String USER_PREFIX = "Your previous reply was not valid. Errors: ";
    static final String USER_SUFFIX = ". Reply again with JSON only.";

    private JsonRepair() {
    }

    public static boolean lengthLimited(String finishReason) {
        return LengthCutoff.isLength(finishReason);
    }

    /** {@code max_tokens} for the length retry. Never above {@link #MAX_TOKENS_CAP}. */
    public static int doubledMaxTokens(int current) {
        int base = current <= 0 ? StructuredOutputSupport.DEFAULT_MAX_TOKENS : current;
        long doubled = (long) base * 2L;
        if (doubled > MAX_TOKENS_CAP) {
            return MAX_TOKENS_CAP;
        }
        return (int) doubled;
    }

    public static String assistantMessage(String reply) {
        return clip(reply == null ? "" : reply, ASSISTANT_LIMIT);
    }

    public static String userMessage(List<String> errors) {
        StringBuilder text = new StringBuilder(USER_PREFIX);
        int count = 0;
        if (errors != null) {
            for (String error : errors) {
                if (error == null || error.isBlank()) {
                    continue;
                }
                if (count >= ERRORS_IN_PROMPT) {
                    break;
                }
                if (count > 0) {
                    text.append("; ");
                }
                text.append(error);
                count++;
            }
        }
        if (count == 0) {
            text.append("$: JSON could not be parsed");
        }
        text.append(USER_SUFFIX);
        return text.toString();
    }

    private static String clip(String text, int codePoints) {
        if (text.codePointCount(0, text.length()) <= codePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }
}
