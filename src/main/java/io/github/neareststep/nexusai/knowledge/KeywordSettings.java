package io.github.neareststep.nexusai.knowledge;

import java.util.List;

/**
 * {@code knowledge.keywords} from {@code config.yml}. Ranges are clamped by {@code PluginConfig}.
 */
public final class KeywordSettings {

    public static final int DEFAULT_MAX_PARAGRAPHS = 6;
    public static final int DEFAULT_MAX_PARAGRAPH_CHARS = 1200;
    public static final int DEFAULT_MAX_FILE_CHARS = 200_000;
    public static final int DEFAULT_MIN_MATCHES = 1;

    public enum OnNoMatch {
        NONE,
        FIRST
    }

    private final int maxParagraphs;
    private final int maxParagraphChars;
    private final int maxFileChars;
    private final int minMatches;
    private final OnNoMatch onNoMatch;
    private final List<String> stopWords;

    public KeywordSettings(
            int maxParagraphs,
            int maxParagraphChars,
            int maxFileChars,
            int minMatches,
            OnNoMatch onNoMatch,
            List<String> stopWords
    ) {
        this.maxParagraphs = maxParagraphs;
        this.maxParagraphChars = maxParagraphChars;
        this.maxFileChars = maxFileChars;
        this.minMatches = minMatches;
        this.onNoMatch = onNoMatch == null ? OnNoMatch.NONE : onNoMatch;
        this.stopWords = stopWords == null || stopWords.isEmpty() ? List.of() : List.copyOf(stopWords);
    }

    public static KeywordSettings defaults() {
        return new KeywordSettings(
                DEFAULT_MAX_PARAGRAPHS,
                DEFAULT_MAX_PARAGRAPH_CHARS,
                DEFAULT_MAX_FILE_CHARS,
                DEFAULT_MIN_MATCHES,
                OnNoMatch.NONE,
                List.of());
    }

    /**
     * Same file cap as full mode. Used by the historical {@code KnowledgeBase.load} entry point
     * so a file is truncated once.
     */
    public static KeywordSettings legacyFileCap(int fileCap) {
        return new KeywordSettings(
                DEFAULT_MAX_PARAGRAPHS,
                DEFAULT_MAX_PARAGRAPH_CHARS,
                Math.max(1, fileCap),
                DEFAULT_MIN_MATCHES,
                OnNoMatch.NONE,
                List.of());
    }

    public int maxParagraphs() {
        return maxParagraphs;
    }

    public int maxParagraphChars() {
        return maxParagraphChars;
    }

    public int maxFileChars() {
        return maxFileChars;
    }

    public int minMatches() {
        return minMatches;
    }

    public OnNoMatch onNoMatch() {
        return onNoMatch;
    }

    public List<String> stopWords() {
        return stopWords;
    }
}
