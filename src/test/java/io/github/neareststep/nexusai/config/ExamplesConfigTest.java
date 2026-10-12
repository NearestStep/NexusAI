package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every NexusAI YAML file under {@code examples/} must load through the current config classes
 * with no unknown-key warning. Host-plugin fragments are checked as YAML only.
 */
class ExamplesConfigTest {

    private static final Set<String> ROOT = Set.of(
            "config-version", "locale", "api", "providers", "model-queue-strategy",
            "model-queue-remaining-threshold", "model-queue", "fallback-model", "knowledge",
            "formats", "cache", "limits", "pool", "prewarm", "moderation", "fallback",
            "dialogue", "actions", "context", "plugin-api", "quotas", "sanitize", "http");

    private static final Set<String> API = Set.of(
            "provider", "model", "base-url", "key", "system-prompt", "temperature", "max-tokens",
            "strip-markdown", "max-answer-chars", "max-answer-lines", "reasoning-effort",
            "connect-timeout", "read-timeout");

    private static final Set<String> PROVIDER = Set.of("type", "url", "api-key", "api-key-file");

    private static final Set<String> FALLBACK_MODEL = Set.of("provider", "model");

    private static final Set<String> KNOWLEDGE = Set.of("max-chars", "max-file-chars", "select", "keywords");

    private static final Set<String> KNOWLEDGE_KEYWORDS = Set.of(
            "max-paragraphs", "max-paragraph-chars", "max-file-chars", "min-matches", "on-no-match", "stop-words");

    private static final Set<String> FORMAT = Set.of(
            "instruction", "max-lines", "max-chars", "max-chars-per-line", "max-words",
            "max-sentences", "strip-markdown", "strip-trailing-punctuation");

    private static final Set<String> CACHE = Set.of("ttl", "max-size");

    private static final Set<String> LIMITS = Set.of(
            "requests-per-minute", "requests-per-day", "player-requests-per-minute",
            "player-requests-per-day", "max-prompt-length", "provider-pause-seconds",
            "auth-pause-seconds", "error-backoff-initial-seconds", "error-backoff-max-seconds",
            "error-log-cooldown-seconds");

    private static final Set<String> POOL = Set.of(
            "enabled", "max-total-prompts", "persist", "save-delay-seconds", "entries");

    private static final Set<String> POOL_ENTRY = Set.of(
            "prompt", "size", "min-threshold", "vars", "system-prompt", "temperature", "max-tokens", "model");

    private static final Set<String> PREWARM = Set.of("enabled", "refresh-before-ttl", "prompts");

    private static final Set<String> MODERATION = Set.of(
            "enabled", "provider", "model", "max-checks-per-minute", "player-cooldown-seconds",
            "min-length", "system-prompt", "temperature", "max-tokens");

    private static final Set<String> DIALOGUE = Set.of(
            "enabled", "memory-turns", "persist-memory", "memory-max-chars", "memory-expiry-hours",
            "session-timeout-seconds", "leave-radius", "max-replies-per-session",
            "message-cooldown-millis", "conversations-per-player-per-day", "max-message-length",
            "cache-greeting", "summary");

    private static final Set<String> SUMMARY = Set.of(
            "enabled", "threshold-turns", "max-chars", "max-tokens", "provider", "model");

    private static final Set<String> ACTIONS = Set.of("enabled", "log", "max-per-reply");

    private static final Set<String> HTTP = Set.of("max-in-flight", "queue-size");

    private static final Set<String> PLUGIN_API = Set.of(
            "enabled", "max-template-chars", "max-var-chars");

    private static final Set<String> QUOTAS = Set.of(
            "enabled", "missing-usage", "server-tokens-per-day", "player-tokens-per-day",
            "groups", "consumers", "save-interval-seconds");

    private static final Set<String> QUOTA_LIMIT = Set.of("tokens-per-day", "requests-per-day");

    private static final Set<String> CONTEXT = Set.of(
            "enabled", "max-provider-timeout-millis", "total-timeout-millis", "max-chars-per-provider",
            "max-chars", "refresh-seconds", "suspend-after-timeouts", "suspend-seconds");

    private static final Set<String> PROMPT_SETTINGS = Set.of(
            "prompt", "vars", "ttl", "fallback", "max-prompt-length", "model", "system-prompt",
            "temperature", "max-tokens", "format", "dialogue", "actions", "knowledge",
            "knowledge-select", "knowledge-keywords",
            "fallback-model", "context");

    private static final Set<String> PROMPT_DIALOGUE = Set.of(
            "greeting", "memory-turns", "session-timeout-seconds", "leave-radius", "max-replies",
            "message-cooldown-millis");

    private static final Set<String> PROMPT_ACTION = Set.of(
            "name", "description", "command", "as", "cooldown-seconds", "daily-limit", "permission");

    private static final Pattern PROMPT_ID = Pattern.compile("[a-z0-9_-]+");

    @Test
    void bundledConfigUsesOnlyKnownKeys() throws IOException {
        Path config = repoFile("src/main/resources/config.yml");
        YamlConfiguration yaml = load(config);
        assertTrue(unknownConfigKeys(yaml).isEmpty(), unknownConfigKeys(yaml).toString());
        PluginConfig loaded = new PluginConfig(yaml);
        assertNull(loaded.modelQueueStrategyWarning());
        assertTrue(loaded.keyFileWarnings().isEmpty(), loaded.keyFileWarnings().toString());

        PromptCatalog.Parsed prompts = PromptCatalog.parse(
                Files.readString(repoFile("src/main/resources/prompts.yml"), StandardCharsets.UTF_8));
        assertTrue(prompts.valid(), prompts.error());
        assertTrue(prompts.warnings().isEmpty(), prompts.warnings().toString());
    }

    @Test
    void examplesLoadWithoutUnknownKeys() throws IOException {
        Path root = repoFile("examples");
        assertTrue(Files.isDirectory(root));
        List<Path> yamlFiles;
        try (Stream<Path> walk = Files.walk(root)) {
            yamlFiles = walk.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml");
            }).sorted().toList();
        }
        assertTrue(yamlFiles.size() >= 8, "expected example YAML files, found " + yamlFiles);

        Path previousBase = PluginConfig.secretsBase;
        try {
            for (Path file : yamlFiles) {
                String name = file.getFileName().toString();
                if (name.equals("config.yml")) {
                    loadExampleConfig(file);
                } else if (name.equals("prompts.yml")) {
                    loadExamplePrompts(file);
                } else {
                    load(file);
                }
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> dirs = walk.filter(Files::isDirectory).sorted().toList();
                for (Path dir : dirs) {
                    if (Files.isRegularFile(dir.resolve("config.yml"))
                            && Files.isRegularFile(dir.resolve("prompts.yml"))) {
                        assertPaired(dir);
                    }
                    if (dir.getParent() != null && dir.getParent().equals(root)) {
                        assertTrue(Files.isRegularFile(dir.resolve("README.md")), "missing README in " + dir);
                    }
                }
            }
        } finally {
            PluginConfig.secretsBase = previousBase;
        }
    }

    private static void loadExampleConfig(Path file) throws IOException {
        PluginConfig.secretsBase = file.getParent();
        YamlConfiguration yaml = load(file);
        List<String> unknown = unknownConfigKeys(yaml);
        assertTrue(unknown.isEmpty(), file + " unknown keys: " + unknown);
        PluginConfig loaded = new PluginConfig(yaml);
        assertNull(loaded.modelQueueStrategyWarning(), file.toString());
        if ("round-robin".equals(yaml.getString("model-queue-strategy"))) {
            assertEquals(QueueStrategy.ROUND_ROBIN, loaded.modelQueueStrategy(), file.toString());
        }
        for (String warning : loaded.keyFileWarnings()) {
            assertTrue(warning.startsWith("chmod 600 "), file + " key file: " + warning);
        }
    }

    private static void loadExamplePrompts(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        YamlConfiguration yaml = load(file);
        List<String> unknown = unknownPromptKeys(yaml);
        assertTrue(unknown.isEmpty(), file + " unknown keys: " + unknown);
        PromptCatalog.Parsed parsed = PromptCatalog.parse(text);
        assertTrue(parsed.valid(), file + " " + parsed.error());
        assertTrue(parsed.warnings().isEmpty(), file + " " + parsed.warnings());
    }

    private static void assertPaired(Path dir) throws IOException {
        PluginConfig.secretsBase = dir;
        YamlConfiguration configYaml = load(dir.resolve("config.yml"));
        PluginConfig config = new PluginConfig(configYaml);
        PromptCatalog.Parsed parsed = PromptCatalog.parse(
                Files.readString(dir.resolve("prompts.yml"), StandardCharsets.UTF_8));
        assertTrue(parsed.valid(), dir.toString());
        List<String> poolIds = config.getPoolEntries().stream().map(PoolEntry::prompt).toList();
        List<String> warnings = new ArrayList<>();
        warnings.addAll(parsed.catalog().unknownIdReferences("pool.entries", poolIds));
        warnings.addAll(parsed.catalog().unknownIdReferences("prewarm.prompts", config.getPrewarmPrompts()));
        warnings.addAll(parsed.catalog().sharedContextWarnings("pool.entries", poolIds));
        warnings.addAll(parsed.catalog().sharedContextWarnings("prewarm.prompts", config.getPrewarmPrompts()));
        assertTrue(warnings.isEmpty(), dir + " " + warnings);
    }

    private static List<String> unknownConfigKeys(YamlConfiguration yaml) {
        List<String> unknown = new ArrayList<>();
        walkConfig(yaml, "", unknown);
        return unknown;
    }

    private static void walkConfig(ConfigurationSection section, String path, List<String> unknown) {
        for (String key : section.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;
            if (!configKeyAllowed(path, key)) {
                unknown.add(full);
                continue;
            }
            if (section.isConfigurationSection(key)) {
                walkConfig(section.getConfigurationSection(key), full, unknown);
                continue;
            }
            if ("model-queue".equals(full)) {
                checkMapList(full, section.getMapList(key), Set.of("provider", "model", "daily-request-limit"), unknown);
            } else if ("pool.entries".equals(full)) {
                checkPoolEntries(section.getMapList(key), unknown);
            } else if ("prewarm.prompts".equals(full)) {
                Object value = section.get(key);
                if (!(value instanceof List<?> list)) {
                    unknown.add(full + " (expected a list)");
                } else {
                    for (Object item : list) {
                        if (item instanceof Map || item instanceof List) {
                            unknown.add(full + " (expected strings)");
                        }
                    }
                }
            }
        }
    }

    private static boolean configKeyAllowed(String parent, String key) {
        if (parent.isEmpty()) {
            return ROOT.contains(key);
        }
        if ("api".equals(parent)) {
            return API.contains(key);
        }
        if ("providers".equals(parent)) {
            return true;
        }
        if (parent.startsWith("providers.") && !parent.substring("providers.".length()).contains(".")) {
            return PROVIDER.contains(key);
        }
        if ("fallback-model".equals(parent)) {
            return FALLBACK_MODEL.contains(key);
        }
        if ("knowledge".equals(parent)) {
            return KNOWLEDGE.contains(key);
        }
        if ("knowledge.keywords".equals(parent)) {
            return KNOWLEDGE_KEYWORDS.contains(key);
        }
        if ("formats".equals(parent)) {
            return "default".equals(key) || FormatPresets.known(key);
        }
        if (parent.startsWith("formats.") && !parent.substring("formats.".length()).contains(".")) {
            return FORMAT.contains(key);
        }
        if ("cache".equals(parent)) {
            return CACHE.contains(key);
        }
        if ("limits".equals(parent)) {
            return LIMITS.contains(key);
        }
        if ("pool".equals(parent)) {
            return POOL.contains(key);
        }
        if ("prewarm".equals(parent)) {
            return PREWARM.contains(key);
        }
        if ("moderation".equals(parent)) {
            return MODERATION.contains(key);
        }
        if ("dialogue".equals(parent)) {
            return DIALOGUE.contains(key);
        }
        if ("dialogue.summary".equals(parent)) {
            return SUMMARY.contains(key);
        }
        if ("actions".equals(parent)) {
            return ACTIONS.contains(key);
        }
        if ("context".equals(parent)) {
            return CONTEXT.contains(key);
        }
        if ("plugin-api".equals(parent)) {
            return PLUGIN_API.contains(key);
        }
        if ("quotas".equals(parent)) {
            return QUOTAS.contains(key);
        }
        if ("quotas.groups".equals(parent) || "quotas.consumers".equals(parent)) {
            return true;
        }
        if (parent.startsWith("quotas.groups.") || parent.startsWith("quotas.consumers.")) {
            return QUOTA_LIMIT.contains(key);
        }
        if ("http".equals(parent)) {
            return HTTP.contains(key);
        }
        if ("sanitize".equals(parent)) {
            return "allow-markup".equals(key);
        }
        return false;
    }

    private static void checkPoolEntries(List<Map<?, ?>> entries, List<String> unknown) {
        int index = 0;
        for (Map<?, ?> map : entries) {
            index++;
            for (Object rawKey : map.keySet()) {
                String name = String.valueOf(rawKey);
                if (!POOL_ENTRY.contains(name)) {
                    unknown.add("pool.entries[" + index + "]." + name);
                }
            }
            Object vars = map.get("vars");
            if (vars != null && !(vars instanceof Map)) {
                unknown.add("pool.entries[" + index + "].vars (expected a map)");
            }
        }
    }

    private static void checkMapList(String path, List<Map<?, ?>> entries, Set<String> allowed, List<String> unknown) {
        int index = 0;
        for (Map<?, ?> map : entries) {
            index++;
            for (Object rawKey : map.keySet()) {
                String name = String.valueOf(rawKey);
                if (!allowed.contains(name)) {
                    unknown.add(path + "[" + index + "]." + name);
                }
            }
        }
    }

    private static List<String> unknownPromptKeys(YamlConfiguration yaml) {
        List<String> unknown = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            if ("config-version".equals(key)) {
                continue;
            }
            if (!PROMPT_ID.matcher(key).matches()) {
                unknown.add(key);
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            for (String setting : section.getKeys(false)) {
                if (!PROMPT_SETTINGS.contains(setting)) {
                    unknown.add(key + "." + setting);
                }
            }
            ConfigurationSection dialogue = section.getConfigurationSection("dialogue");
            if (dialogue != null) {
                for (String setting : dialogue.getKeys(false)) {
                    if (!PROMPT_DIALOGUE.contains(setting)) {
                        unknown.add(key + ".dialogue." + setting);
                    }
                }
            }
            ConfigurationSection fallbackModel = section.getConfigurationSection("fallback-model");
            if (fallbackModel != null) {
                for (String setting : fallbackModel.getKeys(false)) {
                    if (!FALLBACK_MODEL.contains(setting)) {
                        unknown.add(key + ".fallback-model." + setting);
                    }
                }
            }
            if (section.isList("actions")) {
                int index = 0;
                for (Map<?, ?> map : section.getMapList("actions")) {
                    index++;
                    for (Object rawKey : map.keySet()) {
                        String name = String.valueOf(rawKey);
                        if (!PROMPT_ACTION.contains(name)) {
                            unknown.add(key + ".actions[" + index + "]." + name);
                        }
                    }
                }
            }
        }
        return unknown;
    }

    private static YamlConfiguration load(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(file + " is not valid YAML: " + e.getMessage(), e);
        }
        return yaml;
    }

    private static Path repoFile(String relative) {
        Path direct = Path.of(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path nested = Path.of("").toAbsolutePath().resolve(relative);
        assertTrue(Files.exists(nested), "missing " + relative + " from " + Path.of("").toAbsolutePath());
        return nested;
    }
}
