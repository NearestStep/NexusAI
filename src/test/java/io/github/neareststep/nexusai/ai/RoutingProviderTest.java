package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingProviderTest {

    @Test
    void rotatesKeysAndSkips401ThenFailsOverOn429() throws Exception {
        if (System.getenv("NEXUSAI_API_KEY") != null && !System.getenv("NEXUSAI_API_KEY").isBlank()) {
            return;
        }
        PluginConfig config = config();
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0), new QueueEntryConfig("groq", "llama", 0)),
                0,
                60_000L,
                300_000L,
                null,
                () -> 10_000L,
                LocalDate::now,
                ZoneId.of("UTC"),
                Logger.getLogger("route-test"));
        List<String> seen = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            seen.add(apiKey + "@" + model);
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new AiRequestException(AiErrorKind.BAD_KEY, 401, "nope", null);
            }
            if (n == 2) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "slow", null, 5L,
                        Map.of("retry-after", List.of("5")));
            }
            return new ChatExchange("ok", Map.of("x-ratelimit-remaining-requests", List.of("0"), "x-ratelimit-reset-requests", List.of("1s")));
        };
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-test"), () -> 10_000L);
        assertEquals("ok", provider.complete("ping").join());
        assertEquals(List.of("sk-one-1111@gpt-4o-mini", "sk-two-2222@gpt-4o-mini", "sk-groq-3333@llama"), seen);
        assertTrue(queue.status(10_000L).get(1).state().startsWith("COOLDOWN"));
        assertEquals("sk-one-1111", config.provider("openai").apiKeys().getFirst());
    }

    @Test
    void exhaustedQueueDoesNotCallTheApi() {
        PluginConfig config = config();
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 1)),
                0,
                1_000L,
                1_000L,
                null,
                () -> 1_000L,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("route-empty"));
        assertTrue(queue.tryConsume(0, 1_000L));
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange("nope", Map.of());
        };
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-empty"), () -> 1_000L);
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> provider.complete("ping").join());
        assertEquals(AiErrorKind.LOCAL_LIMIT, AiErrors.classify(error));
        assertEquals(0, calls.get());
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.auth-pause-seconds", 300);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", java.util.List.of("sk-one-1111", "sk-two-2222"));
        yaml.createSection("providers.groq");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://api.groq.com/openai/v1");
        yaml.set("providers.groq.api-key", "sk-groq-3333");
        yaml.set("model-queue", java.util.List.of(
                java.util.Map.of("provider", "openai", "model", "gpt-4o-mini"),
                java.util.Map.of("provider", "groq", "model", "llama")
        ));
        return new PluginConfig(yaml);
    }
}
