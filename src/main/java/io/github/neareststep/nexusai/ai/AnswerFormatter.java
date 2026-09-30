package io.github.neareststep.nexusai.ai;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Optional post-processing for holograms and menus: drop markdown, then cap lines and length.
 */
public final class AnswerFormatter {

    private static final Pattern FENCED = Pattern.compile("(?s)```[a-zA-Z0-9_-]*\\R?(.*?)```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s*");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*|__([^_]+)__");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)|(?<!_)_([^_]+)_(?!_)");
    private static final Pattern STRIKE = Pattern.compile("~~([^~]+)~~");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^[ \\t]*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern QUOTE_MARKER = Pattern.compile("(?m)^[ \\t]*>\\s?");

    private AnswerFormatter() {
    }

    public static String format(String raw, boolean stripMarkdown, int maxChars, int maxLines) {
        String text = raw == null ? "" : raw.trim();
        if (stripMarkdown) {
            text = stripMarkdown(text).trim();
        }
        if (maxLines > 0) {
            String[] lines = text.split("\\R", -1);
            if (lines.length > maxLines) {
                text = String.join("\n", Arrays.copyOf(lines, maxLines)).trim();
            }
        }
        if (maxChars > 0 && text.length() > maxChars) {
            if (maxChars == 1) {
                text = "…";
            } else {
                text = text.substring(0, maxChars - 1).stripTrailing() + "…";
            }
        }
        return text;
    }

    public static String stripMarkdown(String text) {
        String stripped = FENCED.matcher(text).replaceAll("$1");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("$1");
        stripped = IMAGE.matcher(stripped).replaceAll("$1");
        stripped = LINK.matcher(stripped).replaceAll("$1");
        stripped = HEADING.matcher(stripped).replaceAll("");
        stripped = BOLD.matcher(stripped).replaceAll(match -> {
            String stars = match.group(1);
            return stars != null ? stars : match.group(2);
        });
        stripped = ITALIC.matcher(stripped).replaceAll(match -> {
            String stars = match.group(1);
            return stars != null ? stars : match.group(2);
        });
        stripped = STRIKE.matcher(stripped).replaceAll("$1");
        stripped = LIST_MARKER.matcher(stripped).replaceAll("");
        stripped = QUOTE_MARKER.matcher(stripped).replaceAll("");
        return stripped;
    }
}
