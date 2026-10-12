package io.github.neareststep.nexusai.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable paragraph index for one load of {@code knowledge/}.
 * Replaced as a whole on {@code /nai reload}. A call already in flight keeps the previous instance.
 */
public final class KnowledgeIndex {

    private static final Pattern COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);
    private static final Pattern KEYWORD_BODY = Pattern.compile("(?is)^\\s*keywords\\s*:\\s*(.*)$");

    private final List<Paragraph> paragraphs;
    private final Map<String, List<Integer>> byFile;
    private final Map<String, List<Integer>> postings;
    private final Map<String, List<String>> byPrefix;
    private final Map<String, Double> idf;
    private final int paragraphCount;

    private KnowledgeIndex(
            List<Paragraph> paragraphs,
            Map<String, List<Integer>> byFile,
            Map<String, List<Integer>> postings,
            Map<String, List<String>> byPrefix,
            Map<String, Double> idf
    ) {
        this.paragraphs = paragraphs;
        this.byFile = byFile;
        this.postings = postings;
        this.byPrefix = byPrefix;
        this.idf = idf;
        this.paragraphCount = paragraphs.size();
    }

    public static KnowledgeIndex build(Map<String, String> files, Tokenizer tokenizer) {
        Tokenizer words = tokenizer == null ? Tokenizer.builtin(List.of()) : tokenizer;
        List<Paragraph> paragraphs = new ArrayList<>();
        Map<String, List<Integer>> byFile = new LinkedHashMap<>();
        if (files != null) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                List<Paragraph> parsed = parseFile(entry.getKey(), entry.getValue(), words);
                for (Paragraph paragraph : parsed) {
                    int id = paragraphs.size();
                    paragraphs.add(paragraph);
                    byFile.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(id);
                }
            }
        }
        Map<String, List<Integer>> postings = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new LinkedHashMap<>();
        for (int id = 0; id < paragraphs.size(); id++) {
            for (String token : paragraphs.get(id).weights.keySet()) {
                postings.computeIfAbsent(token, ignored -> new ArrayList<>()).add(id);
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        Map<String, List<String>> byPrefix = new LinkedHashMap<>();
        for (String token : postings.keySet()) {
            if (token.length() >= 5) {
                byPrefix.computeIfAbsent(token.substring(0, 5), ignored -> new ArrayList<>()).add(token);
            }
        }
        int total = paragraphs.size();
        Map<String, Double> idf = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            int df = Math.max(1, entry.getValue());
            idf.put(entry.getKey(), Math.log((total + 1.0d) / df));
        }
        return new KnowledgeIndex(
                List.copyOf(paragraphs),
                copyIds(byFile),
                copyIds(postings),
                copyTokens(byPrefix),
                Map.copyOf(idf));
    }

    public static KnowledgeIndex empty() {
        return build(Map.of(), Tokenizer.builtin(List.of()));
    }

    public int paragraphCount() {
        return paragraphCount;
    }

    public Paragraph paragraph(int id) {
        return paragraphs.get(id);
    }

    public List<Integer> idsInFile(String file) {
        List<Integer> ids = byFile.get(file);
        return ids == null ? List.of() : ids;
    }

    public List<Integer> postings(String token) {
        if (token == null) {
            return List.of();
        }
        List<Integer> ids = postings.get(token);
        return ids == null ? List.of() : ids;
    }

    public List<String> tokensWithPrefix(String prefix) {
        if (prefix == null) {
            return List.of();
        }
        List<String> tokens = byPrefix.get(prefix);
        return tokens == null ? List.of() : tokens;
    }

    public double idf(String token) {
        if (token == null) {
            return 0.0d;
        }
        Double value = idf.get(token);
        return value == null ? 0.0d : value;
    }

    private static Map<String, List<Integer>> copyIds(Map<String, List<Integer>> source) {
        Map<String, List<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static Map<String, List<String>> copyTokens(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static List<Paragraph> parseFile(String file, String text, Tokenizer tokenizer) {
        String[] lines = text.split("\\R", -1);
        List<Paragraph> parsed = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        List<String> pendingKeywords = new ArrayList<>();
        int number = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                number = flush(file, heading, body, pendingKeywords, tokenizer, parsed, number);
                continue;
            }
            if (line.charAt(0) == '#') {
                number = flush(file, heading, body, pendingKeywords, tokenizer, parsed, number);
                heading = stripTrailing(line);
                continue;
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(line);
        }
        flush(file, heading, body, pendingKeywords, tokenizer, parsed, number);
        return parsed;
    }

    private static int flush(
            String file,
            String heading,
            StringBuilder body,
            List<String> pendingKeywords,
            Tokenizer tokenizer,
            List<Paragraph> parsed,
            int number
    ) {
        String raw = body.toString().strip();
        body.setLength(0);
        if (raw.isEmpty()) {
            return number;
        }
        List<String> keywordBodies = new ArrayList<>(pendingKeywords);
        pendingKeywords.clear();
        String shown = stripComments(raw, keywordBodies);
        if (shown.isBlank()) {
            pendingKeywords.addAll(keywordBodies);
            return number;
        }
        number++;
        String headingText = heading.replaceFirst("^#+\\s*", "");
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String token : tokenizer.tokens(shown)) {
            weights.merge(token, 1, Math::max);
        }
        for (String token : tokenizer.tokens(headingText)) {
            weights.merge(token, 2, Math::max);
        }
        for (String keywordBody : keywordBodies) {
            String payload = keywordPayload(keywordBody);
            for (String token : tokenizer.tokensKeepingStops(payload)) {
                weights.merge(token, 3, Math::max);
            }
        }
        parsed.add(new Paragraph(file, number, heading, shown.strip(), Map.copyOf(weights)));
        return number;
    }

    private static String stripComments(String raw, List<String> keywordBodies) {
        Matcher matcher = COMMENT.matcher(raw);
        StringBuilder visible = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            visible.append(raw, last, matcher.start());
            String body = matcher.group(1) == null ? "" : matcher.group(1);
            if (KEYWORD_BODY.matcher(body).matches() || keywordLabel(body)) {
                keywordBodies.add(body);
            }
            last = matcher.end();
        }
        visible.append(raw, last, raw.length());
        return visible.toString().strip();
    }

    private static boolean keywordLabel(String body) {
        String trimmed = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("keywords");
    }

    static String keywordPayload(String body) {
        if (body == null) {
            return "";
        }
        Matcher matcher = KEYWORD_BODY.matcher(body.trim());
        if (matcher.matches()) {
            String payload = matcher.group(1);
            return payload == null ? "" : payload;
        }
        return body;
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0) {
            char ch = line.charAt(end - 1);
            if (ch != ' ' && ch != '\t' && ch != '\r') {
                break;
            }
            end--;
        }
        return line.substring(0, end);
    }

    public static final class Paragraph {
        private final String file;
        private final int number;
        private final String heading;
        private final String text;
        private final Map<String, Integer> weights;

        Paragraph(String file, int number, String heading, String text, Map<String, Integer> weights) {
            this.file = file;
            this.number = number;
            this.heading = heading == null ? "" : heading;
            this.text = text == null ? "" : text;
            this.weights = weights == null ? Map.of() : weights;
        }

        public String file() {
            return file;
        }

        public int number() {
            return number;
        }

        public String heading() {
            return heading;
        }

        public String text() {
            return text;
        }

        public int weight(String token) {
            if (token == null) {
                return 0;
            }
            Integer weight = weights.get(token);
            return weight == null ? 0 : weight;
        }
    }
}
