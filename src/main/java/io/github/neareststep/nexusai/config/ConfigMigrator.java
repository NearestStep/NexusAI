package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ordered config migrations. A missing {@code config-version} is version 0 (NexusAI 0.6.0).
 * User values are kept. A backup is written before the first byte changes.
 */
public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    public record Outcome(String yaml, int fromVersion, int toVersion, List<String> changes, boolean changed) {
        public Outcome {
            yaml = yaml == null ? "" : yaml;
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        static Outcome same(String yaml, int version) {
            return new Outcome(yaml, version, version, List.of(), false);
        }
    }

    public static Outcome migrateConfig(String yaml) {
        return migrate(yaml, ConfigMigrator::stepConfig);
    }

    public static Outcome migratePrompts(String yaml) {
        return migrate(yaml, ConfigMigrator::stepPrompts);
    }

    public static Outcome migratePool(String yaml) {
        return migrate(yaml, ConfigMigrator::stepPool);
    }

    public static Outcome migrateUsage(String yaml) {
        return migrate(yaml, ConfigMigrator::stepUsage);
    }

    /**
     * @return human-readable notes, empty when the file was already current or missing
     */
    public static List<String> migrateFile(Path file, Function<String, Outcome> migrator, Logger logger) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        Logger log = logger == null ? Logger.getLogger("nexusai.migrate") : logger;
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            Outcome outcome = migrator.apply(original);
            if (!outcome.changed()) {
                return List.of();
            }
            Path backup = FileBackup.backup(file);
            Files.writeString(file, outcome.yaml(), StandardCharsets.UTF_8);
            String note = file.getFileName() + " migrated from version " + outcome.fromVersion()
                    + " to " + outcome.toVersion() + " (backup " + backup.getFileName() + "): "
                    + String.join("; ", outcome.changes());
            log.info(note);
            return List.copyOf(outcome.changes());
        } catch (RuntimeException | IOException e) {
            log.log(Level.WARNING, "Left " + file.getFileName() + " unchanged because migration failed", e);
            return List.of();
        }
    }

    private static Outcome migrate(String yaml, Function<String, Step> step) {
        String current = yaml == null ? "" : yaml;
        if (!parses(current)) {
            return Outcome.same(current, 0);
        }
        int version = readVersion(current);
        int from = version;
        List<String> changes = new ArrayList<>();
        while (version < ConfigVersions.CURRENT) {
            Step applied = step.apply(current);
            if (!applied.changed()) {
                break;
            }
            current = applied.yaml();
            changes.addAll(applied.changes());
            version++;
        }
        if (changes.isEmpty()) {
            return Outcome.same(yaml == null ? "" : yaml, from);
        }
        return new Outcome(current, from, version, changes, true);
    }

    private static Step stepConfig(String yaml) {
        YamlConfiguration doc = load(yaml);
        if (doc == null) {
            return Step.unchanged(yaml);
        }
        List<String> changes = new ArrayList<>();
        String provider = text(doc, "api.provider", "openai").toLowerCase(Locale.ROOT);
        if (provider.isBlank()) {
            provider = "openai";
        }
        String model = text(doc, "api.model", "gpt-4o-mini");
        String baseUrl = text(doc, "api.base-url", "");
        String key = doc.getString("api.key", "");
        if (key == null) {
            key = "";
        }
        String addition = "";
        if (!doc.contains("providers")) {
            addition += renderProviders(provider, baseUrl, key);
            changes.add("added providers and moved api.provider, api.base-url, and api.key into providers." + provider
                    + " without removing api.*");
        }
        if (!doc.contains("model-queue")) {
            addition += "model-queue:\n  - provider: " + YamlStrings.quote(provider)
                    + "\n    model: " + YamlStrings.quote(model.isBlank() ? "gpt-4o-mini" : model) + "\n";
            changes.add("added model-queue from api.provider and api.model");
        }
        if (!doc.contains("model-queue-remaining-threshold")) {
            addition += "model-queue-remaining-threshold: 0\n";
            changes.add("added model-queue-remaining-threshold");
        }
        if (!doc.contains("formats")) {
            addition += FormatPresets.defaultYaml();
            changes.add("added formats");
        }
        String withVersion = yaml;
        if (!doc.contains("config-version")) {
            withVersion = insertVersionLine(yaml);
            changes.add("set config-version to 1");
        } else if (doc.getInt("config-version", 0) < ConfigVersions.CURRENT) {
            withVersion = replaceVersion(yaml, ConfigVersions.CURRENT);
            changes.add("set config-version to 1");
        }
        if (changes.isEmpty()) {
            return Step.unchanged(yaml);
        }
        String merged = appendBlock(withVersion, addition);
        return new Step(merged, changes, true);
    }

    private static Step stepPrompts(String yaml) {
        YamlConfiguration doc = load(yaml);
        if (doc == null || doc.contains("config-version")) {
            return Step.unchanged(yaml);
        }
        return new Step(insertVersionLine(yaml), List.of("set config-version to 1"), true);
    }

    private static Step stepPool(String yaml) {
        YamlConfiguration doc = load(yaml);
        if (doc == null || readVersion(yaml) >= ConfigVersions.CURRENT) {
            return Step.unchanged(yaml);
        }
        String rewritten = rewritePool(doc);
        return new Step(rewritten, List.of("rewrote answers as double-quoted strings and set config-version to 1"), true);
    }

    private static Step stepUsage(String yaml) {
        YamlConfiguration doc = load(yaml);
        if (doc == null || doc.contains("config-version")) {
            return Step.unchanged(yaml);
        }
        return new Step(insertVersionLine(yaml), List.of("set config-version to 1"), true);
    }

    static String renderProviders(String activeId, String activeBaseUrl, String activeKey) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("providers:\n");
        for (String id : ProviderCatalog.IDS) {
            boolean active = id.equals(activeId);
            String url = active && activeBaseUrl != null && !activeBaseUrl.isBlank()
                    ? trimTrailingSlash(activeBaseUrl.trim())
                    : ProviderCatalog.officialUrl(id);
            String key = active && activeKey != null ? activeKey : "";
            yaml.append("  ").append(id).append(":\n");
            yaml.append("    type: ").append(ProviderCatalog.typeFor(id)).append('\n');
            yaml.append("    url: ").append(YamlStrings.quote(url)).append('\n');
            yaml.append("    api-key: ").append(YamlStrings.quote(key)).append('\n');
        }
        if (!ProviderCatalog.IDS.contains(activeId)) {
            String url = activeBaseUrl != null && !activeBaseUrl.isBlank()
                    ? trimTrailingSlash(activeBaseUrl.trim())
                    : "";
            yaml.append("  ").append(activeId).append(":\n");
            yaml.append("    type: ").append(ProviderCatalog.TYPE_OPENAI).append('\n');
            yaml.append("    url: ").append(YamlStrings.quote(url)).append('\n');
            yaml.append("    api-key: ").append(YamlStrings.quote(activeKey == null ? "" : activeKey)).append('\n');
        }
        return yaml.toString();
    }

    static String rewritePool(YamlConfiguration doc) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("config-version: ").append(ConfigVersions.CURRENT).append('\n');
        List<Map<?, ?>> rows = doc.getMapList("pools");
        if (rows.isEmpty()) {
            yaml.append("pools: []\n");
            return yaml.toString();
        }
        yaml.append("pools:\n");
        for (Map<?, ?> row : rows) {
            Object promptValue = row.get("prompt");
            if (promptValue == null) {
                continue;
            }
            String prompt = String.valueOf(promptValue);
            yaml.append("  - prompt: ").append(YamlStrings.quote(prompt)).append('\n');
            Object format = row.get("format");
            if (format != null && !String.valueOf(format).isBlank()) {
                yaml.append("    format: ").append(YamlStrings.quote(String.valueOf(format))).append('\n');
            }
            yaml.append("    answers:\n");
            Object answers = row.get("answers");
            if (answers instanceof List<?> list) {
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    String text = String.valueOf(item);
                    if (text.isBlank()) {
                        continue;
                    }
                    yaml.append("      - ").append(YamlStrings.quote(text)).append('\n');
                }
            }
        }
        return yaml.toString();
    }

    private static String appendBlock(String yaml, String addition) {
        if (addition == null || addition.isEmpty()) {
            return ensureTrailingNewline(yaml);
        }
        String base = yaml == null ? "" : yaml;
        if (!base.isEmpty() && !base.endsWith("\n") && !base.endsWith("\r\n")) {
            base = base + "\n";
        }
        if (!base.isEmpty() && !base.endsWith("\n\n") && !base.endsWith("\r\n\r\n")) {
            base = base + (base.contains("\r\n") ? "\r\n" : "\n");
        }
        String newline = base.contains("\r\n") ? "\r\n" : "\n";
        String block = addition.replace("\r\n", "\n").replace("\n", newline);
        return base + block;
    }

    private static String insertVersionLine(String yaml) {
        String newline = yaml != null && yaml.contains("\r\n") ? "\r\n" : "\n";
        String normalized = yaml == null ? "" : yaml.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        int insertAt = 0;
        while (insertAt < lines.length) {
            String trimmed = lines[insertAt].stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                insertAt++;
                continue;
            }
            break;
        }
        List<String> rewritten = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) {
                rewritten.add("config-version: " + ConfigVersions.CURRENT);
            }
            rewritten.add(lines[i]);
        }
        if (insertAt >= lines.length) {
            rewritten.add("config-version: " + ConfigVersions.CURRENT);
        }
        String joined = String.join(newline, rewritten);
        if (yaml != null && (yaml.endsWith("\n") || yaml.endsWith("\r\n") || yaml.isEmpty()) && !joined.endsWith(newline)) {
            joined = joined + newline;
        }
        return joined;
    }

    private static String replaceVersion(String yaml, int version) {
        String newline = yaml.contains("\r\n") ? "\r\n" : "\n";
        String normalized = yaml.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].stripLeading();
            if (trimmed.startsWith("config-version:") && !lines[i].startsWith(" ") && !lines[i].startsWith("\t")) {
                lines[i] = "config-version: " + version;
                break;
            }
        }
        return String.join(newline, lines);
    }

    private static String ensureTrailingNewline(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return "";
        }
        if (yaml.endsWith("\n") || yaml.endsWith("\r\n")) {
            return yaml;
        }
        return yaml + (yaml.contains("\r\n") ? "\r\n" : "\n");
    }

    private static int readVersion(String yaml) {
        YamlConfiguration doc = load(yaml);
        if (doc == null || !doc.contains("config-version")) {
            return 0;
        }
        return doc.getInt("config-version", 0);
    }

    private static boolean parses(String yaml) {
        return load(yaml == null ? "" : yaml) != null;
    }

    private static YamlConfiguration load(String yaml) {
        YamlConfiguration doc = new YamlConfiguration();
        try {
            doc.load(new StringReader(yaml == null ? "" : yaml));
            return doc;
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    private static String text(YamlConfiguration doc, String path, String fallback) {
        String value = doc.getString(path, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record Step(String yaml, List<String> changes, boolean changed) {
        static Step unchanged(String yaml) {
            return new Step(yaml, List.of(), false);
        }
    }
}
