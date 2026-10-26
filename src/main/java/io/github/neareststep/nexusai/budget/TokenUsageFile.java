package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.config.SecretMask;
import io.github.neareststep.nexusai.config.YamlStrings;
import io.github.neareststep.nexusai.text.Utf16;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.io.StringReader;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code token-usage.yml} text. Keys are written in a fixed order. The document is masked
 * with the configured secrets before it is returned.
 */
final class TokenUsageFile {

    static final int FORMAT = 1;
    static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final String[] COUNTER_FIELDS = {"requests", "prompt", "completion", "total", "estimated"};

    private TokenUsageFile() {
    }

    static String render(TokenLedger.Snapshot snapshot, OffsetDateTime updated, Iterable<String> secrets) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# NexusAI token usage. Written by the plugin; edits are overwritten.\n");
        yaml.append("format: ").append(FORMAT).append('\n');
        yaml.append("day: ").append(quote(String.valueOf(snapshot.day()))).append('\n');
        yaml.append("updated: ").append(quote(updated == null ? "" : UPDATED.format(updated))).append('\n');
        writeCounts(yaml, "server", snapshot.server(), 0);
        writeMap(yaml, "providers", snapshot.providers());
        writeMap(yaml, "rows", snapshot.rows());
        writeMap(yaml, "fallback", snapshot.fallback());
        writeMap(yaml, "origins", snapshot.origins());
        writeMap(yaml, "consumers", snapshot.consumers());
        writeMap(yaml, "players", snapshot.players());
        yaml.append("history:\n");
        if (snapshot.history().isEmpty()) {
            yaml.setLength(yaml.length() - "history:\n".length());
            yaml.append("history: []\n");
        } else {
            for (TokenLedger.DayTotal day : snapshot.history()) {
                yaml.append("  - day: ").append(quote(String.valueOf(day.day()))).append('\n');
                yaml.append("    requests: ").append(day.requests()).append('\n');
                yaml.append("    prompt: ").append(day.prompt()).append('\n');
                yaml.append("    completion: ").append(day.completion()).append('\n');
                yaml.append("    total: ").append(day.total()).append('\n');
                yaml.append("    estimated: ").append(day.estimated()).append('\n');
            }
        }
        return SecretMask.redact(yaml.toString(), secrets);
    }

    static TokenLedger.Snapshot parse(String text) {
        if (text == null || text.isBlank()) {
            throw new CorruptFile("empty");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception e) {
            throw new CorruptFile(e.getClass().getSimpleName());
        }
        if (yaml.getInt("format", -1) != FORMAT) {
            throw new CorruptFile("format");
        }
        LocalDate day;
        try {
            day = LocalDate.parse(yaml.getString("day", ""));
        } catch (DateTimeParseException e) {
            throw new CorruptFile("day");
        }
        Map<String, Map<String, TokenLedger.Counts>> summed = summedMaps(text);
        return new TokenLedger.Snapshot(
                day,
                readCounts(yaml.getConfigurationSection("server")),
                map(summed, yaml, "providers"),
                map(summed, yaml, "rows"),
                map(summed, yaml, "fallback"),
                map(summed, yaml, "origins"),
                map(summed, yaml, "consumers"),
                map(summed, yaml, "players"),
                readHistory(yaml));
    }

    private static void writeMap(StringBuilder yaml, String name, Map<String, TokenLedger.Counts> values) {
        if (values == null || values.isEmpty()) {
            yaml.append(name).append(": {}\n");
            return;
        }
        yaml.append(name).append(":\n");
        for (Map.Entry<String, TokenLedger.Counts> entry : cleanKeys(values).entrySet()) {
            yaml.append("  ").append(quote(entry.getKey())).append(":\n");
            writeCounts(yaml, null, entry.getValue(), 4);
        }
    }

    /**
     * The map sorted by key, with lone surrogates in keys replaced by U+FFFD. Two keys that become
     * the same are summed, so the file never gets a duplicate key.
     */
    static TreeMap<String, TokenLedger.Counts> cleanKeys(Map<String, TokenLedger.Counts> values) {
        TreeMap<String, TokenLedger.Counts> sorted = new TreeMap<>();
        for (Map.Entry<String, TokenLedger.Counts> entry : values.entrySet()) {
            TokenLedger.Counts counts = entry.getValue() == null ? TokenLedger.Counts.zero() : entry.getValue();
            sorted.merge(Utf16.replaceLoneSurrogates(entry.getKey() == null ? "" : entry.getKey()), counts,
                    (left, right) -> new TokenLedger.Counts(
                            left.requests() + right.requests(),
                            left.prompt() + right.prompt(),
                            left.completion() + right.completion(),
                            left.total() + right.total(),
                            left.estimated() + right.estimated()));
        }
        return sorted;
    }

    private static void writeCounts(StringBuilder yaml, String name, TokenLedger.Counts counts, int indent) {
        TokenLedger.Counts safe = counts == null ? TokenLedger.Counts.zero() : counts;
        long[] values = {safe.requests(), safe.prompt(), safe.completion(), safe.total(), safe.estimated()};
        if (name != null) {
            yaml.append(name).append(":\n");
            indent = 2;
        }
        String pad = " ".repeat(indent);
        for (int i = 0; i < COUNTER_FIELDS.length; i++) {
            yaml.append(pad).append(COUNTER_FIELDS[i]).append(": ").append(values[i]).append('\n');
        }
    }

    private static final List<String> MAP_SECTIONS =
            List.of("providers", "rows", "fallback", "origins", "consumers", "players");

    private static Map<String, TokenLedger.Counts> map(
            Map<String, Map<String, TokenLedger.Counts>> summed,
            YamlConfiguration yaml,
            String name
    ) {
        Map<String, TokenLedger.Counts> values = summed == null ? null : summed.get(name);
        return values != null ? values : readMap(yaml.getConfigurationSection(name));
    }

    /**
     * The breakdown maps read from the node tree, where a key that is written twice is still
     * there twice. A file from 1.3.0 or older can hold the same key twice (two names that
     * differed only in a lone surrogate were both written as {@code ?}); those rows are summed
     * instead of the last one winning. {@code null} when the tree cannot be read, and the
     * caller then uses the normal loader.
     */
    static Map<String, Map<String, TokenLedger.Counts>> summedMaps(String text) {
        Node root;
        try {
            root = new Yaml(new LoaderOptions()).compose(new StringReader(text));
        } catch (RuntimeException e) {
            return null;
        }
        if (!(root instanceof MappingNode mapping)) {
            return null;
        }
        Map<String, Map<String, TokenLedger.Counts>> out = new HashMap<>();
        for (NodeTuple section : mapping.getValue()) {
            if (!(section.getKeyNode() instanceof ScalarNode name) || !MAP_SECTIONS.contains(name.getValue())) {
                continue;
            }
            Map<String, TokenLedger.Counts> rows = new LinkedHashMap<>();
            if (section.getValueNode() instanceof MappingNode entries) {
                for (NodeTuple entry : entries.getValue()) {
                    if (!(entry.getKeyNode() instanceof ScalarNode key)) {
                        return null;
                    }
                    rows.merge(key.getValue(), counts(entry.getValueNode()), TokenUsageFile::sum);
                }
            } else if (!(section.getValueNode() instanceof ScalarNode)) {
                return null;
            }
            out.merge(name.getValue(), rows, (left, right) -> {
                Map<String, TokenLedger.Counts> both = new LinkedHashMap<>(left);
                right.forEach((key, value) -> both.merge(key, value, TokenUsageFile::sum));
                return both;
            });
        }
        return out;
    }

    private static TokenLedger.Counts counts(Node node) {
        long[] values = new long[COUNTER_FIELDS.length];
        if (node instanceof MappingNode fields) {
            for (NodeTuple field : fields.getValue()) {
                if (!(field.getKeyNode() instanceof ScalarNode key) || !(field.getValueNode() instanceof ScalarNode value)) {
                    continue;
                }
                for (int i = 0; i < COUNTER_FIELDS.length; i++) {
                    if (COUNTER_FIELDS[i].equals(key.getValue())) {
                        values[i] = parseCount(value.getValue());
                    }
                }
            }
        }
        return new TokenLedger.Counts(values[0], values[1], values[2], values[3], values[4]);
    }

    private static long parseCount(String raw) {
        try {
            return nonNegative(Long.parseLong(raw.trim()));
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static TokenLedger.Counts sum(TokenLedger.Counts left, TokenLedger.Counts right) {
        return new TokenLedger.Counts(
                left.requests() + right.requests(),
                left.prompt() + right.prompt(),
                left.completion() + right.completion(),
                left.total() + right.total(),
                left.estimated() + right.estimated());
    }

    private static Map<String, TokenLedger.Counts> readMap(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, TokenLedger.Counts> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key, readCounts(section.getConfigurationSection(key)));
        }
        return values;
    }

    private static TokenLedger.Counts readCounts(ConfigurationSection section) {
        if (section == null) {
            return TokenLedger.Counts.zero();
        }
        return new TokenLedger.Counts(
                nonNegative(section.getLong("requests", 0L)),
                nonNegative(section.getLong("prompt", 0L)),
                nonNegative(section.getLong("completion", 0L)),
                nonNegative(section.getLong("total", 0L)),
                nonNegative(section.getLong("estimated", 0L)));
    }

    private static List<TokenLedger.DayTotal> readHistory(YamlConfiguration yaml) {
        List<TokenLedger.DayTotal> history = new ArrayList<>();
        for (Map<?, ?> row : yaml.getMapList("history")) {
            Object rawDay = row.get("day");
            if (rawDay == null) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(String.valueOf(rawDay));
            } catch (DateTimeParseException e) {
                continue;
            }
            history.add(new TokenLedger.DayTotal(
                    day,
                    number(row.get("requests")),
                    number(row.get("prompt")),
                    number(row.get("completion")),
                    number(row.get("total")),
                    number(row.get("estimated"))));
            if (history.size() >= TokenLedger.MAX_HISTORY) {
                break;
            }
        }
        return history;
    }

    private static long number(Object raw) {
        if (raw instanceof Number number) {
            return nonNegative(number.longValue());
        }
        return 0L;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    static String quote(String key) {
        return YamlStrings.quote(key);
    }

    static final class CorruptFile extends RuntimeException {
        private CorruptFile(String reason) {
            super(reason);
        }
    }
}
