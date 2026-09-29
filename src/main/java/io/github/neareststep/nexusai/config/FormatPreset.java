package io.github.neareststep.nexusai.config;

/**
 * Editable output preset. Zero means "no limit" for every numeric field.
 */
public record FormatPreset(
        String id,
        String instruction,
        int maxLines,
        int maxChars,
        int maxCharsPerLine,
        int maxWords,
        int maxSentences,
        boolean stripMarkdown,
        boolean stripTrailingPunctuation
) {

    public FormatPreset {
        id = id == null ? "simple" : id;
        instruction = instruction == null ? "" : instruction;
        maxLines = Math.max(0, maxLines);
        maxChars = Math.max(0, maxChars);
        maxCharsPerLine = Math.max(0, maxCharsPerLine);
        maxWords = Math.max(0, maxWords);
        maxSentences = Math.max(0, maxSentences);
    }
}
