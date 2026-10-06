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
            assertEquals("****aaaa, ****bbbb (env)", config.maskedApiKeys());
            assertEquals(KeySource.ENV, config.provider("openai").keySource());
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
    void redactReplacesSecretsIncludingThoseTooShortForASuffix() {
        assertEquals("token ****9999", SecretMask.redact("token sk-secret-9999", List.of("sk-secret-9999")));
        assertEquals("**** stays", SecretMask.redact("ab stays", List.of("ab")));
        assertEquals("cabinet stays", SecretMask.redact("cabinet stays", List.of("ab")));
        assertEquals("Bearer ****", SecretMask.redact("Bearer c4ry", List.of("c4ry")));
        assertEquals("xc4ry stays", SecretMask.redact("xc4ry stays", List.of("c4ry")));
        assertEquals("****", SecretMask.mask("ab"));
        assertEquals("****", SecretMask.mask("key4"));
        assertEquals("****bcde", SecretMask.mask("abcde"));
    }

    @Test
    void vendorShapedKeysAreMaskedAndOrdinaryTextIsNot() {
        String openAi = "sk-qaUnconfigured9999zz";
        String groq = "gsk_Q1w2E3r4T5y6U7i8O9p0A1s2";
        String google = "AIza" + "FAKE0EXAMPLE0KEY0for0tests0onlyrstu";
        String sentence = "The smith asked about the sky and the task-list. sk-iron stays in the chest.";
        String noDigit = "sk-abcdefghijklmnopqrst";
        String configured = "sk-abcdefghijklmnopqrst1";
        String combined = sentence + " " + openAi + " " + groq + " " + google + " " + noDigit;
        String masked = SecretMask.redact(combined, List.of());
        assertFalse(masked.contains(openAi), masked);
        assertFalse(masked.contains(groq), masked);
        assertFalse(masked.contains(google), masked);
        assertTrue(masked.contains("****99zz"), masked);
        assertTrue(masked.contains("****A1s2"), masked);
        assertTrue(masked.contains("****rstu"), masked);
        String lowerGroq = "gsk_" + "a".repeat(19) + "2";
        assertEquals(lowerGroq, SecretMask.redact(lowerGroq, List.of()));
        String digitGoogle = "AIza" + "1".repeat(35);
        assertEquals(digitGoogle, SecretMask.redact(digitGoogle, List.of()));
        assertTrue(masked.contains(noDigit), masked);
        assertTrue(masked.contains("sk-iron"), masked);
        assertTrue(masked.contains("task-list"), masked);
        assertTrue(masked.contains("The smith asked about the sky"), masked);
        assertEquals(sentence, SecretMask.redact(sentence, List.of()));
        assertFalse(SecretMask.redact("AIza" + "1".repeat(34), List.of()).contains("****"));
        String configuredMasked = SecretMask.redact("token " + configured, List.of(configured));
        assertEquals("token ****rst1", configuredMasked);
        assertFalse(configuredMasked.contains(configured));
    }

    @Test
    void vendorKeyCorpusLeavesReadableTextAndMasksRealKeys() {
        String prose = "AIza" + "ReadTheSmithingGuideBeforeNightogYe";
        assertEquals(35, prose.substring(4).length());
        List<String> unchanged = List.of(
                "Use sk-learn for that.",
                "pip install scikit-learn sk-learn-1.5.2 now",
                "The sk-8 board is fast.",
                "Just ask-me anything.",
                "Ticket task-1234567890abcdefghij is open.",
                "File risk-assessment-2024-final-version-v3 is ready.",
                "Order desk-1234567890abcdefghijk today.",
                "Try whisk-2024-recipe-v1-final-edition please.",
                "Config kiosk-2024-terminal-config-v12-final loaded.",
                "Branch sk-learn-pipeline-v2-2024-final was merged.",
                "Read sk-hynix-2024-q3-earnings-report first.",
                "Post sk-tips-for-new-players-part-2 on the forum.",
                "Flag task-sk-1234567890abcdefghijk set.",
                "Run it with --sk-1234567890abcdefghij enabled.",
                "Смотри sk-learn и gsk_test в коде, кузнец.",
                "Кузнец сказал: возьми sk-инструменты и иди в шахту 12345.",
                "123e4567-e89b-12d3-a456-426614174000",
                "6eee52304b255396fbc16b80eeb15101981cd2a7",
                "c2stbGVhcm4tMTIzNDU2Nzg5MGFiY2RlZmdoaWo=",
                "QUJD+sk-abc123def456ghi789jkl0/ZZ==",
                "https://example.com/sk-learn/docs",
                "https://example.com/download/sk-12345678901234567890abc.zip",
                "minecraft:diamond_sword minecraft:netherite_upgrade_smithing_template",
                "QABot1 Sk_Player2024",
                "x=-1234 y=64 z=5678",
                "deadbeefcafebabe0123456789abcdef0123456789abcdef",
                "AIza is a strange word.",
                prose,
                "gsk_config_value_2024",
                "my_gsk_token_ABCDEFGHIJ1234567890",
                "gsk_ABCDEFGHIJKLMNOPQRSTU1",
                "sk_learn_1234567890abcdefghij",
                "SK-1234567890ABCDEFGHIJK",
                "sk-abcdefghijklmnopqrstuvwxyz");
        assertEquals(34, unchanged.size());
        for (String line : unchanged) {
            assertEquals(line, SecretMask.redact(line, List.of()), line);
        }

        String anthropic = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
        String openAi = "sk-qaUnconfigured9999zz";
        String project = "sk-proj-AbCdEfGhIjKlMn9pQrStUv";
        String groq = "gsk_Q1w2E3r4T5y6U7i8O9p0A1s2";
        String google = "AIza" + "FAKE0EXAMPLE0KEY0for0tests0onlyrstu";
        String quoted = "`" + openAi + "`";
        assertEquals(35, google.substring(4).length());
        assertMasked(anthropic, "****6789");
        assertMasked(openAi, "****99zz");
        assertMasked(project, "****StUv");
        assertMasked(groq, "****A1s2");
        assertMasked(google, "****rstu");
        assertEquals("`****99zz`", SecretMask.redact(quoted, List.of()));

        String inPath = "https://example.com/download/" + project + ".zip";
        String afterHyphen = "note-" + project;
        assertFalse(SecretMask.redact(inPath, List.of()).contains(project));
        assertTrue(SecretMask.redact(inPath, List.of()).contains("****StUv.zip"));
        assertEquals("note-****StUv", SecretMask.redact(afterHyphen, List.of()));
    }

    private static void assertMasked(String token, String maskedTail) {
        String masked = SecretMask.redact("see " + token + " now", List.of());
        assertFalse(masked.contains(token), masked);
        assertTrue(masked.contains(maskedTail), masked);
    }

    @Test
    void aShortKeyIsMaskedWarnedAndRedactedFromA401Body() {
        YamlConfiguration yaml = base();
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", "c4ry");
        PluginConfig config = new PluginConfig(yaml);
        assertEquals(List.of("c4ry"), config.configuredSecrets());
        assertEquals("****", config.maskedApiKeys());
        assertFalse(config.maskedApiKeys().contains("c4ry"));
        List<String> warnings = config.shortKeyWarnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("providers.openai"));
        assertTrue(warnings.getFirst().contains("4 characters"));
        assertFalse(warnings.getFirst().contains("c4ry"));
        String body = SecretMask.redact("HTTP 401 unauthorized. The API key was rejected: c4ry", config.configuredSecrets());
        assertFalse(body.contains("c4ry"), body);
        assertTrue(body.contains("****"), body);
    }

    @Test
    void httpLimitsDefaultToSixtyFourAndRejectNonPositive() {
        YamlConfiguration missing = base();
        PluginConfig defaults = new PluginConfig(missing);
        assertEquals(64, defaults.httpMaxInFlight());
        assertEquals(64, defaults.httpQueueSize());
        assertTrue(defaults.httpLimitWarnings().isEmpty());

        YamlConfiguration yaml = base();
        yaml.set("http.max-in-flight", 16);
        yaml.set("http.queue-size", 8);
        PluginConfig configured = new PluginConfig(yaml);
        assertEquals(16, configured.httpMaxInFlight());
        assertEquals(8, configured.httpQueueSize());
        assertTrue(configured.httpLimitWarnings().isEmpty());

        YamlConfiguration bad = base();
        bad.set("http.max-in-flight", 0);
        bad.set("http.queue-size", -1);
        PluginConfig rejected = new PluginConfig(bad);
        assertEquals(64, rejected.httpMaxInFlight());
        assertEquals(64, rejected.httpQueueSize());
        assertEquals(2, rejected.httpLimitWarnings().size());
        String inFlight = rejected.httpLimitWarnings().get(0);
        String queue = rejected.httpLimitWarnings().get(1);
        assertTrue(inFlight.contains("http.max-in-flight"), inFlight);
        assertTrue(inFlight.contains("is 0"), inFlight);
        assertTrue(inFlight.contains("Using 64"), inFlight);
        assertFalse(inFlight.contains("http.queue-size"), inFlight);
        assertTrue(queue.contains("http.queue-size"), queue);
        assertTrue(queue.contains("is -1"), queue);
        assertTrue(queue.contains("Using 64"), queue);
        assertFalse(queue.contains("http.max-in-flight"), queue);
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
