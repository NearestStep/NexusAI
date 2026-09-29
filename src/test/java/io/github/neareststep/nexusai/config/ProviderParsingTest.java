package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderParsingTest {

    @Test
    void keyListEnvSubstitutionAndMasking() {
        assertEquals("****", SecretMask.mask("abcd"));
        assertEquals("****cret", SecretMask.mask("super-secret"));
        assertEquals("", SecretMask.mask("  "));
        Map<String, String> env = Map.of(
                "OPENAI_HOST", "gateway.test",
                "OPENAI_KEY_A", "sk-live-aaaa",
                "OPENAI_KEY_B", "sk-live-bbbb");
        Function<String, String> previous = PluginConfig.environment;
        PluginConfig.environment = env::get;
        try {
            YamlConfiguration yaml = base();
            yaml.set("providers.openai.type", "openai-compatible");
            yaml.set("providers.openai.url", "https://${OPENAI_HOST}/v1");
            yaml.set("providers.openai.api-key", List.of("${OPENAI_KEY_A}", "${OPENAI_KEY_B}", ""));
            yaml.set("providers.gemini.type", "gemini");
            yaml.set("providers.gemini.url", "");
            yaml.set("providers.gemini.api-key", "");
            PluginConfig config = new PluginConfig(yaml);
            assertEquals("https://gateway.test/v1", config.getBaseUrl());
            assertEquals(List.of("sk-live-aaaa", "sk-live-bbbb"), config.provider("openai").apiKeys());
            assertEquals("gemini", config.provider("gemini").type());
            assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", config.provider("gemini").url());
            assertEquals("****aaaa, ****bbbb", config.maskedApiKeys());
            assertFalse(config.maskedApiKeys().contains("sk-live"));
        } finally {
            PluginConfig.environment = previous;
        }
    }

    @Test
    void configuredKeyListRoundRobinSourcePreservesOrder() {
        String previousHost = System.getProperty("nexusai.test");
        YamlConfiguration yaml = base();
        yaml.set("api.provider", "openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", List.of("sk-alpha-1111", "sk-beta-2222"));
        yaml.set("providers.gemini.type", "gemini");
        yaml.set("providers.gemini.url", "https://generativelanguage.googleapis.com/v1beta/openai");
        yaml.set("providers.gemini.api-key", "");
        PluginConfig config = new PluginConfig(yaml);
        ProviderSettings openai = config.provider("openai");
        assertEquals(List.of("sk-alpha-1111", "sk-beta-2222"), openai.apiKeys());
        assertEquals("openai-compatible", openai.type());
        assertEquals("gemini", config.provider("gemini").type());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", config.provider("gemini").url());
        assertEquals("", config.provider("gemini").apiKeys().stream().findFirst().orElse(""));
        assertEquals("****1111, ****2222", config.maskedApiKeys());
        assertTrue(previousHost == null || previousHost.equals(System.getProperty("nexusai.test")));
    }

    @Test
    void urlEnvIsAppliedWhenTheVariableIsInjectedThroughTheSubstitutor() {
        assertEquals("http://localhost:9/v1", EnvSubstitutor.apply("http://${HOST}/v1", Map.of("HOST", "localhost:9")::get));
    }

    @Test
    void unsetEnvVarBecomesEmptyAndIsNotKeptAsTheKey() {
        Function<String, String> previous = PluginConfig.environment;
        PluginConfig.environment = name -> null;
        try {
            YamlConfiguration yaml = base();
            yaml.set("providers.openai.type", "openai-compatible");
            yaml.set("providers.openai.url", "https://api.openai.com/v1");
            yaml.set("providers.openai.api-key", "${NXAI_FIXTURE_KEY}");
            PluginConfig config = new PluginConfig(yaml);
            assertTrue(config.provider("openai").apiKeys().isEmpty());
            assertEquals(List.of("NXAI_FIXTURE_KEY"), config.missingEnvVars());
            assertFalse(config.maskedApiKeys().contains("NXAI_FIXTURE_KEY"));
            assertFalse(config.maskedApiKeys().contains("${"));
        } finally {
            PluginConfig.environment = previous;
        }
    }

    @Test
    void redactReplacesSecretsLongerThanFourCharacters() {
        assertEquals("token ****9999", SecretMask.redact("token sk-secret-9999", List.of("sk-secret-9999")));
        assertEquals("ab stays", SecretMask.redact("ab stays", List.of("ab")));
        assertEquals("****", SecretMask.mask("ab"));
        assertEquals("****", SecretMask.mask("key4"));
    }

    private static YamlConfiguration base() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "");
        yaml.set("api.key", "");
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 100);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        return yaml;
    }
}
