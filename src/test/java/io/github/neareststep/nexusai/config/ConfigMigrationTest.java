package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigrationTest {

    private static final String V06 = """
            # keep-me
            locale: de
            api:
              provider: groq
              model: llama-3.3-70b-versatile
              base-url: "https://example.test/v1/"
              key: "sk-user-secret-1234"
              system-prompt: "Be brief"
              temperature: 0.2
              max-tokens: 64
            cache:
              ttl: 120
            fallback: "hold"
            pool:
              entries:
                - prompt: survival_tips
                  size: 3
                  min-threshold: 1
            """;

    @Test
    void bundledConfigFilesParse() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(Path.of("src/main/resources/config.yml").toFile());
        assertEquals(1, config.getInt("config-version"));
        assertEquals(256, config.getInt("api.max-tokens"));
        assertEquals("openai-compatible", config.getString("providers.openai.type"));
        assertEquals("https://api.openai.com/v1", config.getString("providers.openai.url"));
        assertEquals("", config.getString("providers.openai.api-key"));
        assertEquals("gemini", config.getString("providers.gemini.type"));
        assertEquals("simple", config.getString("formats.default"));
        assertEquals(40, config.getInt("formats.hologram.max-chars-per-line"));
        assertEquals(6, config.getInt("formats.gui.max-lines"));
        var prompts = io.github.neareststep.nexusai.prompt.PromptCatalog.parse(
                Files.readString(Path.of("src/main/resources/prompts.yml")));
        assertTrue(prompts.valid(), prompts.error());
        assertTrue(prompts.warnings().isEmpty(), prompts.warnings().toString());
    }

    @Test
    void migrates060ConfigWithoutDroppingValues() {
        ConfigMigrator.Outcome outcome = ConfigMigrator.migrateConfig(V06);
        assertTrue(outcome.changed());
        assertEquals(0, outcome.fromVersion());
        assertEquals(ConfigVersions.CURRENT, outcome.toVersion());
        assertTrue(outcome.yaml().contains("# keep-me"));
        assertTrue(outcome.changes().stream().noneMatch(change -> change.contains("sk-user-secret-1234")));

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(outcome.yaml());
        } catch (Exception e) {
            throw new AssertionError(outcome.yaml(), e);
        }
        assertEquals(1, yaml.getInt("config-version"));
        assertEquals("de", yaml.getString("locale"));
        assertEquals("Be brief", yaml.getString("api.system-prompt"));
        assertEquals(0.2d, yaml.getDouble("api.temperature"), 0.0001);
        assertEquals(64, yaml.getInt("api.max-tokens"));
        assertEquals("sk-user-secret-1234", yaml.getString("api.key"));
        assertEquals("hold", yaml.getString("fallback"));
        assertEquals(120, yaml.getInt("cache.ttl"));
        assertEquals("survival_tips", yaml.getMapList("pool.entries").getFirst().get("prompt"));
        assertEquals("openai-compatible", yaml.getString("providers.groq.type"));
        assertEquals("https://example.test/v1", yaml.getString("providers.groq.url"));
        assertEquals("sk-user-secret-1234", yaml.getString("providers.groq.api-key"));
        assertEquals("https://api.openai.com/v1", yaml.getString("providers.openai.url"));
        assertEquals("", yaml.getString("providers.openai.api-key"));
        assertEquals("gemini", yaml.getString("providers.gemini.type"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", yaml.getString("providers.gemini.url"));
        assertEquals("groq", String.valueOf(yaml.getMapList("model-queue").getFirst().get("provider")));
        assertEquals("llama-3.3-70b-versatile", String.valueOf(yaml.getMapList("model-queue").getFirst().get("model")));
        assertEquals("simple", yaml.getString("formats.default"));
        assertTrue(yaml.contains("formats.hologram.max-chars-per-line"));

        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("NEXUSAI_API_KEY") == null || System.getenv("NEXUSAI_API_KEY").isBlank());
        PluginConfig loaded = new PluginConfig(yaml);
        assertEquals("groq", loaded.getProvider());
        assertEquals("https://example.test/v1", loaded.getBaseUrl());
        assertEquals("sk-user-secret-1234", loaded.getApiKey());
        assertEquals("llama-3.3-70b-versatile", loaded.modelQueue().getFirst().model());
        assertEquals("****1234", loaded.maskedApiKeys());
    }

    @Test
    void missingMaxTokensIsAppendedWithTheBackupRule() throws Exception {
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        String without = defaults.replace("  max-tokens: 256\n", "");
        assertFalse(without.contains("max-tokens: 256"));
        Path dir = Files.createTempDirectory("nexusai-max-tokens");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, without, StandardCharsets.UTF_8);
        Path existingBak = dir.resolve("config.yml.bak");
        Files.writeString(existingBak, "OLD\n", StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("max-tokens-merge");

        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertTrue(outcome.addedKeys().contains("api.max-tokens"));
        assertEquals("OLD\n", Files.readString(existingBak, StandardCharsets.UTF_8));
        assertTrue(outcome.backup().getFileName().toString().startsWith("config.yml.bak."));
        assertEquals(without, Files.readString(outcome.backup(), StandardCharsets.UTF_8));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(256, yaml.getInt("api.max-tokens"));
        assertEquals(80, yaml.getInt("moderation.max-tokens"));
        assertEquals(PluginConfig.DEFAULT_MAX_TOKENS, new PluginConfig(yaml).getMaxTokens());

        String keptZero = defaults.replace("  max-tokens: 256\n", "  max-tokens: 0\n");
        Path kept = dir.resolve("kept.yml");
        Files.writeString(kept, keptZero, StandardCharsets.UTF_8);
        ConfigStartup.Outcome unchanged = ConfigStartup.prepareConfig(kept, defaults, logger);
        assertTrue(unchanged.addedKeys().isEmpty());
        YamlConfiguration zero = new YamlConfiguration();
        zero.loadFromString(Files.readString(kept, StandardCharsets.UTF_8));
        assertEquals(0, zero.getInt("api.max-tokens"));
        assertEquals(null, new PluginConfig(zero).getMaxTokens());

        String legacy = V06.replace("  max-tokens: 64\n", "");
        assertFalse(legacy.contains("max-tokens:"));
        Path legacyFile = dir.resolve("legacy.yml");
        Files.writeString(legacyFile, legacy, StandardCharsets.UTF_8);
        ConfigStartup.Outcome migrated = ConfigStartup.prepareConfig(legacyFile, defaults, logger);
        assertTrue(migrated.valid());
        assertTrue(migrated.addedKeys().contains("api.max-tokens"));
        List<Path> backups = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("legacy.yml.bak"))
                .toList();
        assertEquals(1, backups.size());
        assertEquals(legacy, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
        YamlConfiguration gained = new YamlConfiguration();
        gained.loadFromString(Files.readString(legacyFile, StandardCharsets.UTF_8));
        assertEquals(256, gained.getInt("api.max-tokens"));
        assertEquals(0.2d, gained.getDouble("api.temperature"), 0.0001);
        assertEquals("sk-user-secret-1234", gained.getString("api.key"));
    }

    @Test
    void migrationAndMissingKeysShareOneBackupPerStart() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-one-backup");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, V06, StandardCharsets.UTF_8);
        String defaults = Files.readString(Path.of("src/main/resources/config.yml"));
        Logger logger = Logger.getLogger("one-backup");
        ConfigStartup.Outcome outcome = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(outcome.valid());
        assertFalse(outcome.addedKeys().isEmpty());
        assertEquals(null, outcome.backup());
        List<Path> backups = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .toList();
        assertEquals(1, backups.size());
        assertEquals(V06, Files.readString(backups.getFirst(), StandardCharsets.UTF_8));
        assertTrue(Files.readString(file).contains("config-version:"));

        ConfigStartup.Outcome second = ConfigStartup.prepareConfig(file, defaults, logger);
        assertTrue(second.valid());
        assertTrue(second.addedKeys().isEmpty());
        long stillOne = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("config.yml.bak"))
                .count();
        assertEquals(1, stillOne);
    }

    @Test
    void secondMigrationIsANoOpAndBackupIsTimestampedWhenOneExists() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-migrate");
        Path file = dir.resolve("config.yml");
        Files.writeString(file, V06, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-test");
        List<String> first = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertFalse(first.isEmpty());
        assertTrue(Files.exists(dir.resolve("config.yml.bak")));
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("sk-user-secret-1234"));
        assertTrue(migrated.contains("# keep-me"));

        List<String> second = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertTrue(second.isEmpty());

        String downgraded = migrated.replace("config-version: 1", "config-version: 0");
        Files.writeString(file, downgraded, StandardCharsets.UTF_8);
        List<String> third = ConfigMigrator.migrateFile(file, ConfigMigrator::migrateConfig, logger);
        assertFalse(third.isEmpty());
        assertTrue(Files.list(dir).anyMatch(path -> path.getFileName().toString().startsWith("config.yml.bak.")));
        assertTrue(Files.readString(file).contains("sk-user-secret-1234"));
    }

    @Test
    void promptsMigrationKeepsIds() {
        String original = """
                # prompts
                survival_tips:
                  prompt: "Stay fed"
                welcome:
                  prompt: "Hello {biome}"
                  vars:
                    biome: "%player_biome%"
                """;
        ConfigMigrator.Outcome outcome = ConfigMigrator.migratePrompts(original);
        assertTrue(outcome.changed());
        assertTrue(outcome.yaml().contains("# prompts"));
        assertTrue(outcome.yaml().contains("survival_tips:"));
        assertTrue(outcome.yaml().contains("biome: \"%player_biome%\"") || outcome.yaml().contains("biome: \"%player_biome%\""));
        var parsed = io.github.neareststep.nexusai.prompt.PromptCatalog.parse(outcome.yaml());
        assertTrue(parsed.valid(), parsed.error());
        assertEquals(List.of("survival_tips", "welcome"), parsed.catalog().ids());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void appendingMissingKeysBacksUpConfigBeforeWriting() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-merge");
        Path file = dir.resolve("config.yml");
        String original = "locale: de\n# keep\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        ConfigMerger.Result result = ConfigMerger.mergeMissing(original, "locale: en\nfallback: \"...\"\n");
        assertFalse(result.addedKeys().isEmpty());
        Path backup = FileBackup.replace(file, result.yaml());
        assertEquals(dir.resolve("config.yml.bak"), backup);
        assertEquals(original, Files.readString(backup, StandardCharsets.UTF_8));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("fallback:"));
        assertTrue(written.contains("# keep"));
        assertTrue(written.contains("locale: de"));

        Path second = FileBackup.replace(file, written + "extra: 1\n");
        assertTrue(second.getFileName().toString().startsWith("config.yml.bak."));
        assertEquals(original, Files.readString(dir.resolve("config.yml.bak"), StandardCharsets.UTF_8));
    }

    @Test
    void migrationRewritesBackUpPromptsPoolAndUsage() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-migrate-all");
        String prompts = "welcome:\n  prompt: \"Hi\"\n";
        String pool = "pools:\n- prompt: tip\n  answers:\n  - hello\n";
        String usage = "today: 1\n";
        Files.writeString(dir.resolve("prompts.yml"), prompts, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("pool.yml"), pool, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("usage.yml"), usage, StandardCharsets.UTF_8);
        Logger logger = Logger.getLogger("migrate-all");
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("prompts.yml"), ConfigMigrator::migratePrompts, logger).isEmpty());
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("pool.yml"), ConfigMigrator::migratePool, logger).isEmpty());
        assertFalse(ConfigMigrator.migrateFile(dir.resolve("usage.yml"), ConfigMigrator::migrateUsage, logger).isEmpty());
        assertEquals(prompts, Files.readString(dir.resolve("prompts.yml.bak"), StandardCharsets.UTF_8));
        assertEquals(pool, Files.readString(dir.resolve("pool.yml.bak"), StandardCharsets.UTF_8));
        assertEquals(usage, Files.readString(dir.resolve("usage.yml.bak"), StandardCharsets.UTF_8));
        assertTrue(Files.readString(dir.resolve("prompts.yml"), StandardCharsets.UTF_8).contains("config-version:"));
        assertTrue(Files.readString(dir.resolve("pool.yml"), StandardCharsets.UTF_8).contains("config-version:"));
        assertTrue(Files.readString(dir.resolve("usage.yml"), StandardCharsets.UTF_8).contains("config-version:"));
    }

    @Test
    void poolMigrationQuotesAnswersAndStillReadsWrappedFiles() throws Exception {
        String old = """
                pools:
                - prompt: "Say: \\"hi\\""
                  answers:
                  - plain answer
                  - 'wrapped line
                    continues here'
                """;
        ConfigMigrator.Outcome outcome = ConfigMigrator.migratePool(old);
        assertTrue(outcome.changed());
        assertTrue(outcome.yaml().contains("config-version: 1"));
        assertTrue(outcome.yaml().contains("\"plain answer\""));
        assertFalse(outcome.yaml().contains("\n    continues"));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(outcome.yaml());
        List<java.util.Map<?, ?>> rows = yaml.getMapList("pools");
        assertEquals("Say: \"hi\"", rows.getFirst().get("prompt"));
        @SuppressWarnings("unchecked")
        List<String> answers = (List<String>) rows.getFirst().get("answers");
        assertEquals("plain answer", answers.get(0));
        assertTrue(answers.get(1).contains("continues here"));
    }
}
