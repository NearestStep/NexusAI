package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginConfigTest {

    private static YamlConfiguration baseYaml() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("api.provider", "openai");
        config.set("api.model", "gpt-4o-mini");
        config.set("api.base-url", "https://api.openai.com/v1/");
        config.set("api.key", "");
        config.set("api.connect-timeout", 5);
        config.set("api.read-timeout", 30);
        config.set("cache.ttl", 300);
        config.set("cache.max-size", 1000);
        config.set("limits.requests-per-minute", 30);
        config.set("limits.requests-per-day", 1000);
        config.set("limits.max-prompt-length", 128);
        config.set("fallback", "...");
        config.set("locale", "en");
        config.set("pool.enabled", true);
        config.set("pool.max-total-prompts", 10);
        config.set("pool.entries", List.of());
        config.set("prewarm.enabled", true);
        config.set("prewarm.refresh-before-ttl", 60);
        config.set("prewarm.prompts", List.of());
        return config;
    }

    @Test
    void emptyKeyMeansNoApiKey() {
        if (hasEnvKey()) {
            return;
        }
        PluginConfig pluginConfig = new PluginConfig(baseYaml());
        assertFalse(pluginConfig.hasApiKey());
        assertEquals(128, pluginConfig.getMaxPromptLength());
        assertEquals("https://api.openai.com/v1", pluginConfig.getBaseUrl());
        assertEquals("en", pluginConfig.getLocale());
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "NEXUSAI_API_KEY", matches = ".+")
    void yamlKeyUsedWhenEnvMissing() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("api.key", "yaml-secret");
        PluginConfig pluginConfig = new PluginConfig(yaml);
        assertTrue(pluginConfig.hasApiKey());
        assertEquals("yaml-secret", pluginConfig.getApiKey());
    }

    @Test
    void groqWithoutBaseUrlUsesProviderDefault() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("api.provider", "groq");
        yaml.set("api.base-url", "");
        PluginConfig pluginConfig = new PluginConfig(yaml);
        assertEquals("https://api.groq.com/openai/v1", pluginConfig.getBaseUrl());
    }

    @Test
    void explicitBaseUrlWinsOverProviderDefault() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("api.provider", "groq");
        yaml.set("api.base-url", "https://custom.example/v1/");
        PluginConfig pluginConfig = new PluginConfig(yaml);
        assertEquals("https://custom.example/v1", pluginConfig.getBaseUrl());
    }

    @Test
    void poolEntriesAreTruncatedToMaxTotalPrompts() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("pool.max-total-prompts", 2);
        yaml.set("pool.entries", List.of(
                Map.of("prompt", "a", "size", 3, "min-threshold", 1),
                Map.of("prompt", "b", "size", 2, "min-threshold", 1),
                Map.of("prompt", "c", "size", 1, "min-threshold", 0)
        ));
        PluginConfig pluginConfig = new PluginConfig(yaml);
        assertEquals(2, pluginConfig.getPoolEntries().size());
        assertEquals("a", pluginConfig.getPoolEntries().get(0).prompt());
        assertEquals("b", pluginConfig.getPoolEntries().get(1).prompt());
    }

    @Test
    void poolEntryVarsAreParsed() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("pool.entries", List.of(
                Map.of(
                        "prompt", "Short warm welcome",
                        "size", 3,
                        "min-threshold", 1,
                        "vars", Map.of("player_name", "%player_name%")
                )
        ));
        PluginConfig pluginConfig = new PluginConfig(yaml);
        assertEquals(1, pluginConfig.getPoolEntries().size());
        PoolEntry entry = pluginConfig.getPoolEntries().get(0);
        assertTrue(entry.hasVars());
        assertEquals("%player_name%", entry.vars().get("player_name"));
    }

    private static boolean hasEnvKey() {
        String env = System.getenv("NEXUSAI_API_KEY");
        return env != null && !env.isBlank();
    }
}
