package io.github.neareststep.nexusai.ai;

import java.time.Duration;

/**
 * Cleans a reply whose provider stopped because {@code finish_reason} is {@code length}.
 * A sentence ending ({@code . ! ? …}, ASCII {@code ...}, and {@code 。！？؟}) is kept when it
 * falls at or past the halfway point of the text. Otherwise the last partial word is dropped
 * and {@code …} is appended. The shared response cache stores that reply for
 * {@link #CACHE_TTL} instead of {@code cache.ttl}, unless the prompt TTL is already shorter.
 */
public final class LengthCutoff {

    /** Shorter than the default {@code cache.ttl} of 300 seconds, long enough to absorb a refreshing placeholder. */
    public static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private static final String CLOSERS = "\"'”’»›)]}）";

    private LengthCutoff() {
    }

    public static boolean isLength(String finishReason) {
        return finishReason != null && "length".equalsIgnoreCase(finishReason.trim());
    }

    public static String trim(String raw) {
        String text = PlayerInput.stripSectionSigns(raw == null ? "" : raw).strip();
        if (text.isEmpty()) {
            return text;
        }
        int sentenceEnd = lastSentenceEnd(text);
        if (sentenceEnd >= 0 && (sentenceEnd + 1L) * 2L >= text.length()) {
            return text.substring(0, sentenceEnd + 1).stripTrailing();
        }
        return trimToWord(text);
    }

    private static int lastSentenceEnd(String text) {
        int best = -1;
        int i = 0;
        while (i < text.length()) {
            int ellipsis = ellipsisLength(text, i);
            if (ellipsis > 0) {
                int end = extendClosers(text, i + ellipsis);
                if (boundary(text, end, text.charAt(i + ellipsis - 1))) {
                    best = end - 1;
                }
                i = end;
                continue;
            }
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (isTerminator(cp) && !decimalPoint(text, i)) {
                int end = extendClosers(text, next);
                if (boundary(text, end, cp)) {
                    best = end - 1;
                }
            }
            i = next;
        }
        return best;
    }

    private static String trimToWord(String text) {
        int lastBreak = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                lastBreak = i;
            }
        }
        String kept = lastBreak >= 0 ? text.substring(0, lastBreak).stripTrailing() : text.strip();
        if (kept.isEmpty()) {
            return "…";
        }
        if (kept.endsWith("…") || kept.endsWith("...")) {
            return kept;
        }
        return kept + "…";
    }

    private static int ellipsisLength(String text, int index) {
        char ch = text.charAt(index);
        if (ch == '…' || ch == '‥') {
            return 1;
        }
        if (text.startsWith("...", index)) {
            return 3;
        }
        return 0;
    }

    private static boolean isTerminator(int codePoint) {
        return codePoint == '.'
                || codePoint == '!'
                || codePoint == '?'
                || codePoint == '。'
                || codePoint == '！'
                || codePoint == '？'
                || codePoint == '؟'
                || codePoint == '‼'
                || codePoint == '⁉'
                || codePoint == '⁈';
    }

    private static boolean decimalPoint(String text, int index) {
        if (text.charAt(index) != '.' || index == 0 || index + 1 >= text.length()) {
            return false;
        }
        return Character.isDigit(text.charAt(index - 1)) && Character.isDigit(text.charAt(index + 1));
    }

    private static int extendClosers(String text, int index) {
        int i = index;
        while (i < text.length() && CLOSERS.indexOf(text.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private static boolean boundary(String text, int index, int terminator) {
        if (index >= text.length() || Character.isWhitespace(text.charAt(index))) {
            return true;
        }
        // CJK and Arabic stops are not followed by a space.
        return terminator == '。'
                || terminator == '！'
                || terminator == '？'
                || terminator == '؟'
                || terminator == '…'
                || terminator == '‥';
    }
}
