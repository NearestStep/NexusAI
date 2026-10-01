package io.github.neareststep.nexusai.ai;

import java.util.regex.Pattern;

/**
 * Cleans a reply whose provider stopped because {@code finish_reason} is {@code length}.
 * A sentence ending ({@code . ! ? …}, ASCII {@code ...}, and {@code 。！？؟}) is kept when it
 * falls at or past the halfway point of the text. Otherwise the last partial word is dropped
 * and {@code …} is appended. A period after a bare number or a standalone Roman numeral at the
 * start of a line, a period that belongs to a common abbreviation, and any terminator on a
 * markdown heading line are not sentence endings. A trailing heading that is only a marker
 * ({@code ### I.}) is dropped. The shared response cache stores the trimmed reply for the
 * normal TTL ({@code cache.ttl}, or the prompt {@code ttl}).
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

    /**
     * Standard Roman form, case-insensitive, at most eight letters. That covers the numerals
     * used as list and heading markers ({@code I} through the tens and a bit beyond) without
     * treating an arbitrary run of {@code I}s as a marker.
     */
    private static final Pattern ROMAN = Pattern.compile(
            "(?i)^M{0,4}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})$");
    private static final int MAX_ROMAN_LENGTH = 8;

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
        String kept;
        if (sentenceEnd >= 0 && (sentenceEnd + 1L) * 2L >= text.length()) {
            kept = text.substring(0, sentenceEnd + 1);
        } else {
            kept = trimToWord(text);
        }
        return dropTrailingIncompleteHeading(kept);
    }

    private static int lastSentenceEnd(String text) {
        int best = -1;
        int i = 0;
        while (i < text.length()) {
            int ellipsis = ellipsisLength(text, i);
            if (ellipsis > 0) {
                int end = extendClosers(text, i + ellipsis);
                if (!headingLine(text, i) && boundary(text, end, text.charAt(i + ellipsis - 1))) {
                    best = end - 1;
                }
                i = end;
                continue;
            }
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (isTerminator(cp) && !ignoredTerminator(text, i)) {
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
     * A mark that must not end a sentence: anything on a markdown heading line, a decimal,
     * a list number or standalone Roman numeral used as a list or heading marker, or the
     * period of a common abbreviation (a closing bracket may follow it).
     */
    private static boolean ignoredTerminator(String text, int index) {
        return headingLine(text, index)
                || decimalPoint(text, index)
                || listItemPeriod(text, index)
                || romanMarkerPeriod(text, index)
                || abbreviation(text, index);
    }

    /**
     * A line whose first non-space character is {@code #} does not end a sentence.
     */
    private static boolean headingLine(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return false;
        }
        int cursor = index;
        while (cursor > 0) {
            char previous = text.charAt(cursor - 1);
            if (previous == '\n' || previous == '\r') {
                break;
            }
            cursor--;
        }
        while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor++;
        }
        return cursor < text.length() && text.charAt(cursor) == '#';
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

    /**
     * {@code I.} and {@code XIV.} at the start of a line, with optional indent and an optional
     * markdown heading prefix ({@code ### I.}). A numeral in the middle of a line, such as
     * {@code chapter I.}, can still end a sentence.
     */
    private static boolean romanMarkerPeriod(String text, int index) {
        if (index < 0 || index >= text.length() || text.charAt(index) != '.') {
            return false;
        }
        int start = index - 1;
        if (start < 0 || !isRomanLetter(text.charAt(start))) {
            return false;
        }
        while (start >= 0 && isRomanLetter(text.charAt(start))) {
            start--;
        }
        int from = start + 1;
        if (from > 0 && isTokenChar(text.charAt(from - 1))) {
            return false;
        }
        if (!isStandaloneRoman(text.substring(from, index))) {
            return false;
        }
        int cursor = from - 1;
        while (cursor >= 0 && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor--;
        }
        while (cursor >= 0 && text.charAt(cursor) == '#') {
            cursor--;
        }
        while (cursor >= 0 && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor--;
        }
        return cursor < 0 || text.charAt(cursor) == '\n' || text.charAt(cursor) == '\r';
    }

    private static boolean isRomanLetter(char ch) {
        return "IVXLCDMivxlcdm".indexOf(ch) >= 0;
    }

    private static boolean isStandaloneRoman(String token) {
        return token != null
                && !token.isEmpty()
                && token.length() <= MAX_ROMAN_LENGTH
                && ROMAN.matcher(token).matches();
    }

    /**
     * Drops a trailing heading that is only hashes, or hashes plus a list marker
     * ({@code ###}, {@code ### I.}, {@code ###…}). A heading that already has title words stays.
     */
    private static String dropTrailingIncompleteHeading(String text) {
        String current = text == null ? "" : text.stripTrailing();
        boolean dropped = false;
        while (!current.isEmpty()) {
            int lineStart = 0;
            for (int i = 0; i < current.length(); i++) {
                char ch = current.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    lineStart = i + 1;
                }
            }
            if (!isIncompleteHeadingLine(current.substring(lineStart))) {
                break;
            }
            dropped = true;
            if (lineStart == 0) {
                return "…";
            }
            current = current.substring(0, lineStart).stripTrailing();
        }
        if (!dropped) {
            return current;
        }
        if (current.isEmpty()) {
            return "…";
        }
        if (current.endsWith("…") || current.endsWith("...")) {
            return current;
        }
        int end = lastSentenceEnd(current);
        if (end == current.length() - 1) {
            return current;
        }
        return current + "…";
    }

    private static boolean isIncompleteHeadingLine(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        if (i >= line.length() || line.charAt(i) != '#') {
            return false;
        }
        while (i < line.length() && line.charAt(i) == '#') {
            i++;
        }
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        int marker = i;
        while (marker < line.length() && isRomanLetter(line.charAt(marker))) {
            marker++;
        }
        boolean roman = marker > i && isStandaloneRoman(line.substring(i, marker));
        int digits = i;
        while (digits < line.length() && Character.isDigit(line.charAt(digits))) {
            digits++;
        }
        boolean number = !roman && digits > i;
        int after = i;
        if (roman) {
            after = marker;
        } else if (number) {
            after = digits;
        }
        if (after < line.length() && line.charAt(after) == '.') {
            after++;
        }
        while (after < line.length() && (line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
            after++;
        }
        if (after < line.length() && line.charAt(after) == '…') {
            after++;
        } else if (line.startsWith("...", after)) {
            after += 3;
        }
        while (after < line.length() && (line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
            after++;
        }
        return after == line.length();
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
