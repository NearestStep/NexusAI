package io.github.neareststep.nexusai.text;

/**
 * Length cuts that keep surrogate pairs whole, and replacement of lone surrogates. A lone
 * surrogate is not valid Unicode: YAML (SnakeYAML 2.2 and 2.6) refuses to write it, and other
 * encoders drop or replace it. Strings without surrogates return as they are, without allocating.
 */
public final class Utf16 {

    /** U+FFFD, what a lone surrogate becomes. */
    public static final char REPLACEMENT = '\uFFFD';

    private Utf16() {
    }

    /**
     * {@code index} moved back by one when it falls between the two halves of a surrogate pair,
     * so {@code text.substring(0, result)} and {@code text.substring(result)} keep the pair whole.
     */
    public static int boundaryBefore(CharSequence text, int index) {
        if (index > 0 && index < text.length()
                && Character.isLowSurrogate(text.charAt(index))
                && Character.isHighSurrogate(text.charAt(index - 1))) {
            return index - 1;
        }
        return index;
    }

    /** {@code index} moved forward by one when it falls between the two halves of a surrogate pair. */
    public static int boundaryAfter(CharSequence text, int index) {
        return boundaryBefore(text, index) == index ? index : index + 1;
    }

    /** The first {@code maxChars} UTF-16 units at most, one fewer when the last one would be half a pair. */
    public static String head(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, boundaryBefore(text, Math.max(0, maxChars)));
    }

    /** The last {@code maxChars} UTF-16 units at most, one fewer when the first one would be half a pair. */
    public static String tail(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(boundaryAfter(text, text.length() - Math.max(0, maxChars)));
    }

    /** True when {@code text} has a high surrogate without a low one after it, or a low one without a high one before it. */
    public static boolean hasLoneSurrogate(CharSequence text) {
        return text != null && firstLone(text) >= 0;
    }

    /** {@code text} with every lone surrogate replaced by U+FFFD. The same instance when there is none. */
    public static String replaceLoneSurrogates(String text) {
        if (text == null) {
            return null;
        }
        int first = firstLone(text);
        if (first < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        out.append(text, 0, first);
        for (int i = first; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(c).append(text.charAt(i + 1));
                i++;
            } else if (Character.isSurrogate(c)) {
                out.append(REPLACEMENT);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int firstLone(CharSequence text) {
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (!Character.isSurrogate(c)) {
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }
}
