package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider or model name with a lone UTF-16 surrogate (a YAML escape such as {@code "\ud83d"}
 * without its pair): one warning per setting, and the name becomes U+FFFD.
 */
class ConfigLoneSurrogateTest {

    @Test
    void configNamesAreCleanedWithOneWarningEach() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                api:
                  provider: groq
                  model: "main\\ud83d"
                providers:
                  groq:
                    api-key: "k"
                model-queue:
                  - provider: groq
                    model: "queue\\ud83d"
                fallback-model:
                  provider: groq
                  model: "qa-fb\\ud83d"
                """);
        PluginConfig config = new PluginConfig(yaml);
        assertEquals("qa-fb\uFFFD", config.fallbackModel().model());
        assertEquals("queue\uFFFD", config.modelQueue().getFirst().model());
        assertEquals("main\uFFFD", config.getModel());
        List<String> warnings = config.identifierWarnings();
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("api.model "), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("model-queue model ")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("fallback-model.model ")), warnings.toString());

        config.reload(yaml);
        assertEquals(3, config.identifierWarnings().size(), "a reload starts a fresh list");
    }

    @Test
    void cleanNamesGiveNoWarning() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                api:
                  provider: groq
                  model: "llama \\U0001F600"
                fallback-model:
                  provider: groq
                  model: qa-fb
                """);
        PluginConfig config = new PluginConfig(yaml);
        assertEquals(List.of(), config.identifierWarnings());
        assertEquals("llama \uD83D\uDE00", config.getModel());
    }

    @Test
    void promptModelAndFallbackAreCleanedWithAWarning() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                tip:
                  prompt: "Give a tip"
                  model: "m\\ud83d"
                  fallback-model:
                    provider: groq
                    model: "qa-fb\\ud83d"
                """);
        assertTrue(parsed.valid(), parsed.error());
        var prompt = parsed.catalog().find("tip").orElseThrow();
        assertEquals("qa-fb\uFFFD", prompt.fallbackModel().model());
        assertEquals("groq|qa-fb\uFFFD", prompt.fallbackModel().storageId());
        assertEquals(2, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.warnings().stream().allMatch(w -> w.contains("lone UTF-16 surrogate")), parsed.warnings().toString());
    }

    @Test
    void recordsCleanNamesFromCode() {
        assertEquals("qa-fb\uFFFD", FallbackModel.of("groq", "qa-fb\ud83d").model());
        assertEquals("q\uFFFD", new QueueEntryConfig("g\ud83d", "q\ud83d", 0).model());
        assertEquals("g\uFFFD", new QueueEntryConfig("g\ud83d", "q", 0).provider());
        assertEquals("m\uFFFD", GenerationOverrides.none().withModel("m\ud83d").model("g"));
    }

    @Test
    void migrationAndMergeKeepAnEscapedSurrogate() throws Exception {
        String old = """
                config-version: 1
                api:
                  provider: groq
                  model: "qa\\ud83d"
                  max-tokens: 0
                """;
        ConfigMigrator.Outcome migrated = ConfigMigrator.migrateConfig(old);
        assertTrue(migrated.changed());
        assertTrue(migrated.yaml().contains("model: \"qa\\ud83d\""), migrated.yaml());
        ConfigMerger.Result merged = ConfigMerger.mergeMissing(migrated.yaml(), "api:\n  read-timeout: 30\nlocale: en\n");
        assertTrue(merged.valid());
        assertTrue(merged.yaml().contains("model: \"qa\\ud83d\""), merged.yaml());
        java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(merged.yaml()));
    }
}
