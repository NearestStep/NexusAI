package io.github.neareststep.nexusai.knowledge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Letters and digits, lowercased with {@link Locale#ROOT}, length at least 2.
 * A run of Han, Hiragana, Katakana, or Hangul is split into adjacent pairs.
 * Built-in English and Russian stop lists are dropped, plus any extra words.
 */
public final class Tokenizer {

    private static final String ENGLISH = "knowledge/stopwords-en.txt";
    private static final String RUSSIAN = "knowledge/stopwords-ru.txt";

    private final Set<String> stopWords;

    private Tokenizer(Set<String> stopWords) {
        this.stopWords = stopWords;
    }

    public static Tokenizer builtin(List<String> extraStopWords) {
        Set<String> raw = new HashSet<>();
        raw.addAll(readResource(ENGLISH));
        raw.addAll(readResource(RUSSIAN));
        if (extraStopWords != null) {
            for (String word : extraStopWords) {
                if (word == null) {
                    continue;
                }
                String trimmed = word.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    raw.add(trimmed);
                }
            }
        }
        Tokenizer open = new Tokenizer(Set.of());
        Set<String> stops = new HashSet<>();
        for (String word : raw) {
            stops.addAll(open.tokenize(word, false));
        }
        stops.removeIf(token -> token == null || token.length() < 2);
        return new Tokenizer(Set.copyOf(stops));
    }

    public Set<String> stopWords() {
        return stopWords;
    }

    /**
     * Unique tokens in first-seen order. Stop words are omitted.
     */
    public List<String> tokens(String text) {
        return tokenize(text, true);
    }

    /**
     * Unique tokens in first-seen order. Stop words are kept.
     * Explicit {@code keywords:} use this so an author word is not discarded.
     */
    public List<String> tokensKeepingStops(String text) {
        return tokenize(text, false);
    }

    public boolean stop(String token) {
        return token != null && stopWords.contains(token);
    }

    private List<String> tokenize(String text, boolean dropStops) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int[] points = lower.codePoints().toArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        int index = 0;
        while (index < points.length) {
            if (isCjk(points[index])) {
                int end = index + 1;
                while (end < points.length && isCjk(points[end])) {
                    end++;
                }
                for (int pair = index; pair + 1 < end; pair++) {
                    add(new String(points, pair, 2), dropStops, seen);
                }
                index = end;
                continue;
            }
            if (isWord(points[index])) {
                int end = index + 1;
                while (end < points.length && isWord(points[end])) {
                    end++;
                }
                if (end - index >= 2) {
                    add(new String(points, index, end - index), dropStops, seen);
                }
                index = end;
                continue;
            }
            index++;
        }
        return List.copyOf(seen);
    }

    private void add(String token, boolean dropStops, Collection<String> seen) {
        if (token.length() < 2) {
            return;
        }
        if (dropStops && stopWords.contains(token)) {
            return;
        }
        seen.add(token);
    }

    static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private static boolean isWord(int codePoint) {
        return Character.isLetterOrDigit(codePoint) && !isCjk(codePoint);
    }

    static int resourceWords(String name) {
        return readResource(name).size();
    }

    private static Set<String> readResource(String name) {
        InputStream in = Tokenizer.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            return Set.of();
        }
        Set<String> words = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                    continue;
                }
                words.add(trimmed);
            }
        } catch (IOException ignored) {
            return Set.of();
        }
        return words;
    }
}
