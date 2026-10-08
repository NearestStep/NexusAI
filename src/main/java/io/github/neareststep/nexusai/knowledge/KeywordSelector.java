package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.knowledge.KeywordSettings.OnNoMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Picks paragraphs from the files a prompt lists. The result for one file list and one
 * normalized query token set is cached (256 entries) until the next reload.
 */
public final class KeywordSelector {

    static final int CACHE_CAPACITY = 256;

    private final KnowledgeIndex index;
    private final Tokenizer tokenizer;
    private final KeywordSettings settings;
    private final int maxChars;
    private final Logger logger;
    private final Map<String, Selection> cache = new LinkedHashMap<>(CACHE_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Selection> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    public KeywordSelector(
            KnowledgeIndex index,
            Tokenizer tokenizer,
            KeywordSettings settings,
            int maxChars,
            Logger logger
    ) {
        this.index = index == null ? KnowledgeIndex.empty() : index;
        this.tokenizer = tokenizer == null ? Tokenizer.builtin(List.of()) : tokenizer;
        this.settings = settings == null ? KeywordSettings.defaults() : settings;
        this.maxChars = Math.max(1, maxChars);
        this.logger = logger == null ? Logger.getLogger("nexusai.knowledge") : logger;
    }

    public KnowledgeIndex index() {
        return index;
    }

    public int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public Selection select(List<String> names, String query, List<String> extraKeywords) {
        List<String> files = orderedFiles(names);
        List<String> tokens = queryTokens(query, extraKeywords);
        String key = cacheKey(files, tokens);
        synchronized (cache) {
            Selection cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Selection computed = compute(files, tokens);
            cache.put(key, computed);
            return computed;
        }
    }

    private Selection compute(List<String> files, List<String> tokens) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (String file : files) {
            order.putIfAbsent(file, order.size());
        }
        List<Rank> ranked = new ArrayList<>(rank(order, tokens));
        if (ranked.isEmpty() && settings.onNoMatch() == OnNoMatch.FIRST) {
            ranked = firstOfEach(order);
        }
        ranked.sort(Comparator.comparingInt(Rank::fileIndex).thenComparingInt(Rank::number));
        String content = render(ranked);
        boolean truncated = false;
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars);
            truncated = true;
            logger.warning("Knowledge text was truncated to " + maxChars + " characters for this request.");
        }
        List<String> labels = new ArrayList<>();
        for (Rank rank : ranked) {
            labels.add(rank.paragraph.file() + "#" + rank.paragraph.number());
        }
        return new Selection(content, List.copyOf(labels), truncated);
    }

    private List<Rank> rank(Map<String, Integer> order, List<String> tokens) {
        if (tokens.isEmpty() || order.isEmpty() || index.paragraphCount() == 0) {
            return List.of();
        }
        Map<Integer, Double> scores = new LinkedHashMap<>();
        Map<Integer, Integer> hits = new LinkedHashMap<>();
        for (String queryToken : tokens) {
            Map<Integer, Match> best = new LinkedHashMap<>();
            offer(queryToken, queryToken, best);
            if (queryToken.length() >= 5) {
                String prefix = queryToken.substring(0, 5);
                for (String documentToken : index.tokensWithPrefix(prefix)) {
                    if (documentToken.length() >= 5 && queryToken.regionMatches(0, documentToken, 0, 5)) {
                        offer(queryToken, documentToken, best);
                    }
                }
            }
            for (Map.Entry<Integer, Match> entry : best.entrySet()) {
                KnowledgeIndex.Paragraph paragraph = index.paragraph(entry.getKey());
                if (!order.containsKey(paragraph.file())) {
                    continue;
                }
                scores.merge(entry.getKey(), entry.getValue().score, Double::sum);
                hits.merge(entry.getKey(), 1, Integer::sum);
            }
        }
        List<Rank> ranked = new ArrayList<>();
        int minMatches = settings.minMatches();
        for (Map.Entry<Integer, Integer> entry : hits.entrySet()) {
            if (entry.getValue() < minMatches) {
                continue;
            }
            KnowledgeIndex.Paragraph paragraph = index.paragraph(entry.getKey());
            Integer fileIndex = order.get(paragraph.file());
            if (fileIndex == null) {
                continue;
            }
            ranked.add(new Rank(scores.getOrDefault(entry.getKey(), 0.0d), fileIndex, paragraph.number(), paragraph));
        }
        ranked.sort(Comparator
                .comparingDouble(Rank::score).reversed()
                .thenComparingInt(Rank::fileIndex)
                .thenComparingInt(Rank::number));
        int limit = Math.min(settings.maxParagraphs(), ranked.size());
        if (limit <= 0) {
            return List.of();
        }
        return new ArrayList<>(ranked.subList(0, limit));
    }

    private void offer(String queryToken, String documentToken, Map<Integer, Match> best) {
        double idf = index.idf(documentToken);
        for (int id : index.postings(documentToken)) {
            KnowledgeIndex.Paragraph paragraph = index.paragraph(id);
            int weight = paragraph.weight(documentToken);
            if (weight <= 0) {
                continue;
            }
            double score = idf * weight;
            boolean exact = queryToken.equals(documentToken);
            Match previous = best.get(id);
            if (previous == null || score > previous.score + 1.0e-9d
                    || (Math.abs(score - previous.score) <= 1.0e-9d && exact && !previous.exact)) {
                best.put(id, new Match(score, exact));
            }
        }
    }

    private List<Rank> firstOfEach(Map<String, Integer> order) {
        List<Rank> chosen = new ArrayList<>();
        for (Map.Entry<String, Integer> file : order.entrySet()) {
            List<Integer> ids = index.idsInFile(file.getKey());
            if (ids.isEmpty()) {
                continue;
            }
            KnowledgeIndex.Paragraph paragraph = index.paragraph(ids.getFirst());
            chosen.add(new Rank(0.0d, file.getValue(), paragraph.number(), paragraph));
        }
        return chosen;
    }

    private String render(List<Rank> ranked) {
        StringBuilder body = new StringBuilder();
        String currentFile = null;
        boolean anyInFile = false;
        for (Rank rank : ranked) {
            KnowledgeIndex.Paragraph paragraph = rank.paragraph;
            if (!paragraph.file().equals(currentFile)) {
                if (!body.isEmpty()) {
                    body.append("\n\n");
                }
                body.append('[').append(paragraph.file()).append(']');
                currentFile = paragraph.file();
                anyInFile = false;
            }
            if (anyInFile) {
                body.append("\n\n");
            } else {
                body.append('\n');
            }
            anyInFile = true;
            if (!paragraph.heading().isEmpty()) {
                body.append(paragraph.heading()).append('\n');
            }
            body.append(limitParagraph(paragraph.text(), settings.maxParagraphChars()));
        }
        return body.toString();
    }

    static String limitParagraph(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int cap = Math.max(1, maxChars);
        if (cap < text.length() && Character.isLowSurrogate(text.charAt(cap))) {
            cap--;
        }
        if (cap <= 0) {
            return "";
        }
        String window = text.substring(0, cap);
        int end = -1;
        for (int i = 0; i < window.length(); i++) {
            char ch = window.charAt(i);
            if (!isTerminator(ch)) {
                continue;
            }
            if (ch == '.' && i > 0 && Character.isDigit(window.charAt(i - 1))) {
                continue;
            }
            end = i;
        }
        if (end >= 0) {
            return stripTrailing(window.substring(0, end + 1));
        }
        return stripTrailing(window);
    }

    private static boolean isTerminator(char ch) {
        return ch == '.' || ch == '!' || ch == '?' || ch == '…'
                || ch == '。' || ch == '！' || ch == '？';
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    private List<String> queryTokens(String query, List<String> extraKeywords) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>(tokenizer.tokens(query));
        if (extraKeywords != null) {
            for (String keyword : extraKeywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                // Listed on the prompt on purpose, so a stop word stays after tokenization.
                tokens.addAll(tokenizer.tokensKeepingStops(keyword));
            }
        }
        return List.copyOf(tokens);
    }

    private static List<String> orderedFiles(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank() || files.contains(name)) {
                continue;
            }
            files.add(name);
        }
        return List.copyOf(files);
    }

    private static String cacheKey(List<String> files, List<String> tokens) {
        List<String> sorted = new ArrayList<>(tokens);
        sorted.sort(String::compareTo);
        return String.join("\n", files) + "\u0000" + String.join("\n", sorted);
    }

    public record Selection(String content, List<String> labels, boolean truncated) {
        public Selection {
            content = content == null ? "" : content;
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    private record Match(double score, boolean exact) {
    }

    private record Rank(double score, int fileIndex, int number, KnowledgeIndex.Paragraph paragraph) {
    }
}
