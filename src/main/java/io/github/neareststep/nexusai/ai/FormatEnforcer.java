package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.FormatPreset;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Server-side limits for a {@link FormatPreset}, applied after the model answers.
 * Length cuts land on a word boundary when the text has one.
 */
public final class FormatEnforcer {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    private FormatEnforcer() {
    }

    public static String enforce(String raw, FormatPreset preset) {
        if (preset == null) {
            return raw == null ? "" : raw.trim();
        }
        String text = raw == null ? "" : raw.trim();
        if (preset.stripMarkdown()) {
            text = AnswerFormatter.stripMarkdown(text).trim();
        }
        if (preset.maxSentences() > 0) {
            text = limitSentences(text, preset.maxSentences());
        }
        if (preset.maxCharsPerLine() > 0) {
            text = wrapLines(text, preset.maxCharsPerLine());
        }
        if (preset.maxLines() > 0) {
            text = limitLines(text, preset.maxLines());
        }
        if (preset.maxChars() > 0) {
            text = truncateWords(text, preset.maxChars());
        }
        if (preset.maxWords() > 0) {
            text = limitWords(text, preset.maxWords());
        }
        if (preset.stripTrailingPunctuation()) {
            text = stripTrailingPunctuation(text);
        }
        return text.trim();
    }

    static String limitSentences(String text, int maxSentences) {
        if (text.isEmpty() || maxSentences < 1) {
            return text;
        }
        String[] parts = SENTENCE_SPLIT.split(text);
        if (parts.length <= maxSentences) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxSentences; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(parts[i].trim());
        }
        return out.toString().trim();
    }

    static String wrapLines(String text, int width) {
        if (width < 1) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            if (line.length() <= width) {
                wrapped.add(line);
                continue;
            }
            String rest = line.trim();
            while (rest.length() > width) {
                int space = rest.lastIndexOf(' ', width);
                if (space <= 0) {
                    wrapped.add(rest.substring(0, width));
                    rest = rest.substring(width).stripLeading();
                } else {
                    wrapped.add(rest.substring(0, space));
                    rest = rest.substring(space + 1).stripLeading();
                }
            }
            if (!rest.isEmpty()) {
                wrapped.add(rest);
            }
        }
        return String.join("\n", wrapped);
    }

    static String limitLines(String text, int maxLines) {
        if (maxLines < 1) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length <= maxLines) {
            return text;
        }
        return String.join("\n", java.util.Arrays.copyOf(lines, maxLines)).trim();
    }

    static String truncateWords(String text, int maxChars) {
        if (maxChars < 1 || text.length() <= maxChars) {
            return text;
        }
        int space = text.lastIndexOf(' ', maxChars);
        int cut = space > 0 ? space : maxChars;
        return text.substring(0, cut).stripTrailing();
    }

    static String limitWords(String text, int maxWords) {
        if (maxWords < 1) {
            return text;
        }
        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWords) {
            return text.trim();
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(words[i]);
        }
        return out.toString();
    }

    static String stripTrailingPunctuation(String text) {
        int end = text.length();
        while (end > 0 && isTrailingPunctuation(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing();
    }

    private static boolean isTrailingPunctuation(char c) {
        return c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':'
                || c == '…' || c == '"' || c == '\'' || c == '”' || c == '’';
    }
}
