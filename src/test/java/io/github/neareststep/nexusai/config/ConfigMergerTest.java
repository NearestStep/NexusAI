package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMergerTest {

    @Test
    void addsMissingKeysWithoutReplacingUserValuesOrComments() {
        String existing = """
                # user header
                locale: ru
                api:
                  # keep this comment
                  provider: groq
                  model: my-model
                  key: "secret"
                limits:
                  requests-per-minute: 4
                fallback: "wait"
                """;
        String defaults = """
                locale: en
                api:
                  provider: openai
                  model: gpt-4o-mini
                  key: ""
                  system-prompt: ""
                  temperature: -1
                limits:
                  requests-per-minute: 30
                  provider-pause-seconds: 60
                fallback: "..."
                pool:
                  persist: true
                """;

        ConfigMerger.Result result = ConfigMerger.mergeMissing(existing, defaults);
        assertTrue(result.addedKeys().contains("api.system-prompt"));
        assertTrue(result.addedKeys().contains("api.temperature"));
        assertTrue(result.addedKeys().contains("limits.provider-pause-seconds"));
        assertTrue(result.addedKeys().contains("pool.persist"));
        assertFalse(result.addedKeys().contains("api.model"));
        assertTrue(result.yaml().contains("# user header"));
        assertTrue(result.yaml().contains("# keep this comment"));

        YamlConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(result.yaml()));
        assertEquals("ru", parsed.getString("locale"));
        assertEquals("groq", parsed.getString("api.provider"));
        assertEquals("my-model", parsed.getString("api.model"));
        assertEquals("secret", parsed.getString("api.key"));
        assertEquals(4, parsed.getInt("limits.requests-per-minute"));
        assertEquals("wait", parsed.getString("fallback"));
        assertEquals("", parsed.getString("api.system-prompt"));
        assertEquals(-1, parsed.getInt("api.temperature"));
        assertEquals(60, parsed.getInt("limits.provider-pause-seconds"));
        assertTrue(parsed.getBoolean("pool.persist"));

        ConfigMerger.Result second = ConfigMerger.mergeMissing(result.yaml(), defaults);
        assertTrue(second.addedKeys().isEmpty());
        assertEquals(result.yaml(), second.yaml());
    }

    @Test
    void brokenYamlIsReturnedUnchanged() {
        String broken = "api: [\n  this is not valid yaml\n";
        String defaults = """
                api:
                  provider: openai
                  model: gpt-4o-mini
                """;
        ConfigMerger.Result result = ConfigMerger.mergeMissing(broken, defaults);
        assertFalse(result.valid());
        assertTrue(result.addedKeys().isEmpty());
        assertEquals(broken, result.yaml());
    }

    @Test
    void insertsNestedSectionInsideAnExistingParentAndKeepsComments() {
        String existing = """
                # user header
                dialogue:
                  # keep this comment
                  enabled: false
                  memory-turns: 4
                sanitize:
                  allow-markup: true
                """;
        String defaults = """
                dialogue:
                  enabled: true
                  memory-turns: 8
                  summary:
                    enabled: false
                    threshold-turns: 2
                context:
                  enabled: true
                  refresh-seconds: 30
                sanitize:
                  allow-markup: false
                """;

        ConfigMerger.Result result = ConfigMerger.mergeMissing(existing, defaults);
        assertTrue(result.addedKeys().contains("dialogue.summary.enabled"));
        assertTrue(result.addedKeys().contains("dialogue.summary.threshold-turns"));
        assertTrue(result.addedKeys().contains("context.enabled"));
        assertTrue(result.addedKeys().contains("context.refresh-seconds"));
        assertFalse(result.addedKeys().contains("dialogue.enabled"));
        assertTrue(result.yaml().contains("# user header"));
        assertTrue(result.yaml().contains("# keep this comment"));

        int dialogue = result.yaml().indexOf("dialogue:");
        int summary = result.yaml().indexOf("summary:");
        int sanitize = result.yaml().indexOf("sanitize:");
        int context = result.yaml().indexOf("\ncontext:");
        assertTrue(dialogue >= 0 && summary > dialogue && summary < sanitize, result.yaml());
        assertTrue(context > sanitize || context > dialogue, result.yaml());

        YamlConfiguration parsed = YamlConfiguration.loadConfiguration(new StringReader(result.yaml()));
        assertEquals(false, parsed.getBoolean("dialogue.enabled"));
        assertEquals(4, parsed.getInt("dialogue.memory-turns"));
        assertEquals(false, parsed.getBoolean("dialogue.summary.enabled"));
        assertEquals(2, parsed.getInt("dialogue.summary.threshold-turns"));
        assertEquals(true, parsed.getBoolean("context.enabled"));
        assertEquals(30, parsed.getInt("context.refresh-seconds"));
        assertEquals(true, parsed.getBoolean("sanitize.allow-markup"));

        ConfigMerger.Result second = ConfigMerger.mergeMissing(result.yaml(), defaults);
        assertTrue(second.addedKeys().isEmpty());
    }
}
