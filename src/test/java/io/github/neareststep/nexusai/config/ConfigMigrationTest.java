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
