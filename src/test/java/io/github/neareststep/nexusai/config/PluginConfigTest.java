package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

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
        return config;
    }

    @Test
    void emptyKeyMeansNoApiKey() {
        // Skip when CI/dev shell already exports a key — env always wins.
        if (hasEnvKey()) {
            return;
        }
        PluginConfig pluginConfig = new PluginConfig(baseYaml());
        assertFalse(pluginConfig.hasApiKey());
        assertEquals(128, pluginConfig.getMaxPromptLength());
        assertEquals("https://api.openai.com/v1", pluginConfig.getBaseUrl());
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

    private static boolean hasEnvKey() {
        String env = System.getenv("NEXUSAI_API_KEY");
        return env != null && !env.isBlank();
    }
}
