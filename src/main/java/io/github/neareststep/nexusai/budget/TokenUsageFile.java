package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.config.SecretMask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
        return new TokenLedger.Snapshot(
                day,
                readCounts(yaml.getConfigurationSection("server")),
                readMap(yaml.getConfigurationSection("providers")),
                readMap(yaml.getConfigurationSection("rows")),
                readMap(yaml.getConfigurationSection("fallback")),
                readMap(yaml.getConfigurationSection("origins")),
                readMap(yaml.getConfigurationSection("consumers")),
                readMap(yaml.getConfigurationSection("players")),
                readHistory(yaml));
    }

    private static void writeMap(StringBuilder yaml, String name, Map<String, TokenLedger.Counts> values) {
        if (values == null || values.isEmpty()) {
            yaml.append(name).append(": {}\n");
            return;
        }
        yaml.append(name).append(":\n");
        for (Map.Entry<String, TokenLedger.Counts> entry : new TreeMap<>(values).entrySet()) {
            yaml.append("  ").append(quote(entry.getKey())).append(":\n");
            writeCounts(yaml, null, entry.getValue(), 4);
        }
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
        String safe = key == null ? "" : key;
        return '"' + safe.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    static final class CorruptFile extends RuntimeException {
        private CorruptFile(String reason) {
            super(reason);
        }
    }
}
