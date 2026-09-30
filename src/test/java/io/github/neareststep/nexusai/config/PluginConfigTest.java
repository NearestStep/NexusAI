package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void ollamaAndOpenRouterHaveDefaultBaseUrls() {
        YamlConfiguration ollama = baseYaml();
        ollama.set("api.provider", "ollama");
        ollama.set("api.base-url", "");
        ollama.set("api.key", "");
        PluginConfig local = new PluginConfig(ollama);
        assertEquals("http://localhost:11434/v1", local.getBaseUrl());
        assertTrue(local.allowsKeylessRequests());
        if (!hasEnvKey()) {
            assertFalse(local.hasApiKey());
            assertTrue(local.canSendRequests());
        }

        YamlConfiguration openRouter = baseYaml();
        openRouter.set("api.provider", "openrouter");
        openRouter.set("api.base-url", "");
        PluginConfig remote = new PluginConfig(openRouter);
        assertEquals("https://openrouter.ai/api/v1", remote.getBaseUrl());
        assertFalse(remote.allowsKeylessRequests());
    }

    @Test
    void localhostAndOllamaPortAllowKeylessRequests() {
        assertTrue(PluginConfig.isLocalBaseUrl("http://127.0.0.1:8080/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://[::1]:11434/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://[::1]:18080/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://10.0.0.8:11434/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://localhost:9/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://0.0.0.0:18080/v1"));
        assertTrue(PluginConfig.isLocalBaseUrl("http://nexusai.local:9/v1"));
        assertFalse(PluginConfig.isLocalBaseUrl("https://api.openai.com/v1"));
        assertFalse(PluginConfig.isLocalBaseUrl("http://203.0.113.10:18080/v1"));
    }

    @Test
    void generationDefaultsOmitOptionalParameters() {
        PluginConfig pluginConfig = new PluginConfig(baseYaml());
        assertEquals(null, pluginConfig.getSystemPrompt());
        assertEquals(null, pluginConfig.getTemperature());
        assertEquals(null, pluginConfig.getMaxTokens());
        assertFalse(pluginConfig.isStripMarkdown());
        assertEquals("low", pluginConfig.getReasoningEffort());
        assertEquals(60, pluginConfig.getProviderPauseSeconds());
        assertEquals(300, pluginConfig.getAuthPauseSeconds());
        assertTrue(pluginConfig.isPoolPersist());
    }

    @Test
    void poolEntryCanOverrideGenerationSettings() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("api.temperature", 0.9);
        yaml.set("api.max-tokens", 400);
        yaml.set("pool.entries", List.of(
                Map.of(
                        "prompt", "Short warm welcome",
                        "size", 3,
                        "min-threshold", 1,
                        "system-prompt", "Be brief",
                        "temperature", 0,
                        "max-tokens", 40
                )
        ));
        PoolEntry entry = new PluginConfig(yaml).getPoolEntries().getFirst();
        assertEquals("Be brief", entry.overrides().systemPrompt("global"));
        assertEquals(0.0, entry.overrides().temperature(0.9), 0.0001);
        assertEquals(40, entry.overrides().maxTokens(400));
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

    @Test
    void queueProviderWithAKeyAllowsRequestsWhenTheActiveProviderHasNone() {
        withClearedApiKey(() -> {
            PluginConfig config = new PluginConfig(keyedQueueYaml());
            assertFalse(config.hasApiKey());
            assertTrue(config.canSendRequests());
            assertEquals("openai: no, mock: yes", config.providerKeyPresence("yes", "no"));
            assertEquals("openai: no, mock: yes", NaiCommand.statusApiKeyText(config, "yes", "no"));
        });
    }

    @Test
    void missingKeyStaysABlockerOnlyWhenNoTargetCanSend() {
        withClearedApiKey(() -> {
            YamlConfiguration yaml = baseYaml();
            yaml.set("providers.openai.type", "openai-compatible");
            yaml.set("providers.openai.url", "https://api.openai.com/v1");
            yaml.set("providers.openai.api-key", "");
            yaml.set("providers.ollama.type", "openai-compatible");
            yaml.set("providers.ollama.url", "http://localhost:11434/v1");
            yaml.set("providers.ollama.api-key", "");
            yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            PluginConfig blocked = new PluginConfig(yaml);
            assertFalse(blocked.canSendRequests());
            assertFalse(blocked.canSendChatRequests());
            assertEquals("no", NaiCommand.statusApiKeyText(blocked, "yes", "no"));
            assertTrue(blocked.credentialWarning().contains("AI requests will not be sent"));
        });
    }

    @Test
    void fallbackModelAndPinnedModerationAndLocalQueueAllowRequests() {
        withClearedApiKey(() -> {
            YamlConfiguration fallback = keyedQueueYaml();
            fallback.set("providers.mock.api-key", "");
            fallback.set("fallback-model.provider", "mock");
            fallback.set("fallback-model.model", "mock-fallback");
            fallback.set("providers.mock.api-key", "fallback-key-ZZ99");
            fallback.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            assertTrue(new PluginConfig(fallback).canSendRequests());

            YamlConfiguration moderation = keyedQueueYaml();
            moderation.set("providers.mock.api-key", "");
            moderation.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            moderation.set("moderation.enabled", true);
            moderation.set("moderation.provider", "mock");
            moderation.set("moderation.model", "mock-mod");
            moderation.set("providers.mock.api-key", "mod-key-ZZ99");
            PluginConfig pinned = new PluginConfig(moderation);
            assertTrue(pinned.canSendRequests());
            assertFalse(pinned.canSendChatRequests());
            assertTrue(pinned.providerKeyPresence("yes", "no").contains("mock: yes"));
            assertTrue(pinned.credentialWarning().contains("Pinned moderation can still run"));
            assertTrue(pinned.credentialWarning().contains("will not be sent"));

            YamlConfiguration local = keyedQueueYaml();
            local.set("providers.mock.api-key", "");
            local.set("providers.mock.url", "http://127.0.0.1:18090/v1");
            local.set("model-queue", List.of(Map.of("provider", "mock", "model", "mock-ok")));
            PluginConfig localQueue = new PluginConfig(local);
            assertTrue(localQueue.canSendRequests());
            assertTrue(localQueue.canSendChatRequests());
            assertNull(localQueue.credentialWarning());
            assertTrue(localQueue.providerKeyPresence("yes", "no").contains("mock: local"));

            YamlConfiguration activeKey = keyedQueueYaml();
            activeKey.set("api.provider", "mock");
            activeKey.set("providers.mock.api-key", "mock-key-QA01");
            PluginConfig keyed = new PluginConfig(activeKey);
            assertTrue(keyed.hasApiKey());
            assertEquals("****QA01", NaiCommand.statusApiKeyText(keyed, "yes", "no"));

            YamlConfiguration disabledPin = keyedQueueYaml();
            disabledPin.set("providers.mock.api-key", "mod-key-ZZ99");
            disabledPin.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            disabledPin.set("moderation.enabled", false);
            disabledPin.set("moderation.provider", "mock");
            disabledPin.set("moderation.model", "mock-mod");
            assertFalse(new PluginConfig(disabledPin).canSendRequests());
        });
    }

    @Test
    void moderationOnlyKeyDoesNotSendOrRecordAProviderError() {
        withClearedApiKey(() -> {
            YamlConfiguration yaml = keyedQueueYaml();
            yaml.set("providers.mock.api-key", "mod-key-ZZ99");
            yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            yaml.set("moderation.enabled", true);
            yaml.set("moderation.provider", "mock");
            yaml.set("moderation.model", "mock-mod");
            PluginConfig config = new PluginConfig(yaml);
            assertTrue(config.canSendRequests());
            assertFalse(config.canSendChatRequests());
            AtomicInteger calls = new AtomicInteger();
            AiProvider provider = prompt -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("should-not-send");
            };
            AiHttpClient client = new AiHttpClient(
                    new AiCache(Duration.ofMinutes(5), 10),
                    provider,
                    config,
                    Logger.getLogger("mod-only"));
            assertTrue(client.testAsync("FBPROBE say something").isCompletedExceptionally());
            assertTrue(client.requestAsync("TIMEPROBE time=morning").isCompletedExceptionally());
            assertEquals(0, calls.get());
            assertTrue(client.lastErrorText() == null || client.lastErrorText().isBlank());
        });
    }

    @Test
    void ollamaWithoutAKeyIsReportedAsLocal() {
        withClearedApiKey(() -> {
            YamlConfiguration yaml = baseYaml();
            yaml.set("providers.openai.type", "openai-compatible");
            yaml.set("providers.openai.url", "https://api.openai.com/v1");
            yaml.set("providers.openai.api-key", "");
            yaml.set("providers.ollama.type", "openai-compatible");
            yaml.set("providers.ollama.url", "http://127.0.0.1:11434/v1");
            yaml.set("providers.ollama.api-key", "");
            yaml.set("model-queue", List.of(
                    Map.of("provider", "openai", "model", "gpt-4o-mini"),
                    Map.of("provider", "ollama", "model", "mock-ok")));
            PluginConfig config = new PluginConfig(yaml);
            assertTrue(config.canSendChatRequests());
            assertEquals("openai: no, ollama: local", config.providerKeyPresence("yes", "no"));
            assertEquals("openai: no, ollama: local", NaiCommand.statusApiKeyText(config, "yes", "no"));
            assertNull(config.credentialWarning());
        });
    }

    private static YamlConfiguration keyedQueueYaml() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", "");
        yaml.set("providers.mock.type", "openai-compatible");
        yaml.set("providers.mock.url", "https://mock.example/v1");
        yaml.set("providers.mock.api-key", "mock-key-QA01");
        yaml.set("model-queue", List.of(Map.of("provider", "mock", "model", "mock-ok")));
        return yaml;
    }

    private static void withClearedApiKey(Runnable body) {
        Function<String, String> previous = PluginConfig.environment;
        PluginConfig.environment = name -> null;
        try {
            body.run();
        } finally {
            PluginConfig.environment = previous;
        }
    }

    private static boolean hasEnvKey() {
        String env = System.getenv("NEXUSAI_API_KEY");
        return env != null && !env.isBlank();
    }
}
