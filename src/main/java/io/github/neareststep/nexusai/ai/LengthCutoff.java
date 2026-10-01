package io.github.neareststep.nexusai.ai;

/**
 * Cleans a reply whose provider stopped because {@code finish_reason} is {@code length}.
 * A sentence ending ({@code . ! ? …}, ASCII {@code ...}, and {@code 。！？؟}) is kept when it
 * falls at or past the halfway point of the text. Otherwise the last partial word is dropped
 * and {@code …} is appended. A period after a bare number at the start of a line, and a period
 * that belongs to a common abbreviation, are not sentence endings. The shared response cache
 * stores the trimmed reply for the normal TTL ({@code cache.ttl}, or the prompt {@code ttl}).
 */
public final class LengthCutoff {

    private static final String CLOSERS = "\"'”’»›)]}）";

    /**
     * Lowercase tokens, including the final period. A closing bracket may follow that period.
     */
    private static final String[] ABBREVIATIONS = {
            "напр.",
            "т.д.",
            "т.п.",
            "т.е.",
            "mrs.",
            "e.g.",
            "i.e.",
            "etc.",
            "гг.",
            "др.",
            "пр.",
            "им.",
            "ул.",
            "см.",
            "г.",
            "vs.",
            "mr.",
            "dr.",
            "st."
    };

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
            if (isTerminator(cp) && !ignoredPeriod(text, i)) {
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

    /**
     * A period that must not end a sentence: a decimal, a list number at the start of a line,
     * or the period of a common abbreviation (a closing bracket may follow it).
     */
    private static boolean ignoredPeriod(String text, int index) {
        return decimalPoint(text, index) || listItemPeriod(text, index) || abbreviation(text, index);
    }

    private static boolean decimalPoint(String text, int index) {
        if (index < 0 || index >= text.length() || text.charAt(index) != '.' || index == 0 || index + 1 >= text.length()) {
            return false;
        }
        return Character.isDigit(text.charAt(index - 1)) && Character.isDigit(text.charAt(index + 1));
    }

    /**
     * {@code 3.} and {@code 12.} at the start of a line, with optional indent. A number in the
     * middle of a line, such as {@code point 3.}, can still end a sentence.
     */
    private static boolean listItemPeriod(String text, int index) {
        if (index < 0 || index >= text.length() || text.charAt(index) != '.') {
            return false;
        }
        int start = index - 1;
        if (start < 0 || !Character.isDigit(text.charAt(start))) {
            return false;
        }
        while (start >= 0 && Character.isDigit(text.charAt(start))) {
            start--;
        }
        int cursor = start;
        while (cursor >= 0 && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor--;
        }
        return cursor < 0 || text.charAt(cursor) == '\n' || text.charAt(cursor) == '\r';
    }

    private static boolean abbreviation(String text, int dot) {
        if (dot < 0 || dot >= text.length() || text.charAt(dot) != '.') {
            return false;
        }
        for (String token : ABBREVIATIONS) {
            int start = dot + 1 - token.length();
            if (start < 0) {
                continue;
            }
            if (start > 0 && isTokenChar(text.charAt(start - 1))) {
                continue;
            }
            if (regionEqualsIgnoreCase(text, start, token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTokenChar(char ch) {
        return Character.isLetter(ch) || Character.isDigit(ch);
    }

    private static boolean regionEqualsIgnoreCase(String text, int start, String expected) {
        if (start < 0 || start + expected.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (Character.toLowerCase(text.charAt(start + i)) != expected.charAt(i)) {
                return false;
            }
        }
        return true;
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
