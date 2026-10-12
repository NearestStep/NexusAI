package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMetadataTest {

    @Test
    void rateLimitThenTheNextRowCountsBothAttempts() {
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            models.add(model);
            if (models.size() == 1) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "slow", null);
            }
            return answered(model);
        };
        ModelAnswer answer = route(http, GenerationOverrides.none());
        assertEquals(List.of("gpt-4o-mini", "llama"), models);
        assertEquals("pong", answer.text());
        assertEquals(2, answer.attempts());
        assertEquals("groq", answer.providerId());
        assertEquals("llama", answer.model());
        assertFalse(answer.fallbackModelUsed());
        assertMetadataCopied(answer);
    }

    @Test
    void unauthorizedKeyRetryCountsAsAnotherAttempt() {
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiRequestException(AiErrorKind.BAD_KEY, 401, "nope", null);
            }
            return answered(model);
        };
        ModelAnswer answer = route(http, GenerationOverrides.none());
        assertEquals(2, calls.get());
        assertEquals(2, answer.attempts());
        assertEquals("openai", answer.providerId());
        assertEquals("gpt-4o-mini", answer.model());
        assertFalse(answer.fallbackModelUsed());
        assertMetadataCopied(answer);
    }

    @Test
    void queueFailureThenFallbackModelIsMarked() {
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            models.add(model);
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.OTHER, 500, "down", null);
            }
            return answered(model);
        };
        ModelAnswer answer = route(
                http,
                GenerationOverrides.none().withFallbackModel("ollama", "llama3.2"),
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)));
        assertEquals(List.of("gpt-4o-mini", "llama3.2"), models);
        assertEquals(2, answer.attempts());
        assertEquals("ollama", answer.providerId());
        assertEquals("llama3.2", answer.model());
        assertTrue(answer.fallbackModelUsed());
        assertMetadataCopied(answer);
    }

    @Test
    void aSkippedRowDoesNotCountAsAnHttpAttempt() {
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return answered(model);
        };
        PluginConfig config = config();
        ModelQueue queue = queue(List.of(new QueueEntryConfig("missing", "nope", 0)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("meta-skip"), () -> 10_000L);
            assertThrows(CompletionException.class,
                    () -> provider.answer("ping", GenerationOverrides.none(), false).join());
            assertEquals(0, calls.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulRoutedReplyIsCachedWithProviderAndModel() {
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> answered(model);
        PluginConfig config = config();
        ModelQueue queue = queue(config.modelQueue());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 20);
        try {
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("meta-cache"), () -> 10_000L);
            AiHttpClient client = new AiHttpClient(cache, provider, config, Logger.getLogger("meta-cache-http"));
            assertEquals("pong", client.requestAsync("hello").join());
            String key = client.cacheKey("hello");
            AiCache.CachedAnswer stored = cache.lookup(key).orElseThrow();
            assertEquals("pong", stored.text());
            assertEquals("openai", stored.providerId());
            assertEquals("gpt-4o-mini", stored.model());
            assertEquals("pong", client.requestAsync("hello").join());
            assertEquals(1, queue.requestsToday(0));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aFailureDoesNotWriteTheCache() {
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            throw new AiRequestException(AiErrorKind.OTHER, 500, "down", null);
        };
        PluginConfig config = config();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 20);
        try {
            RoutingProvider provider = new RoutingProvider(
                    config, queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0))), http, executor,
                    Logger.getLogger("meta-fail"), () -> 10_000L);
            AiHttpClient client = new AiHttpClient(cache, provider, config, Logger.getLogger("meta-fail-http"));
            assertThrows(CompletionException.class, () -> client.requestAsync("hello").join());
            assertTrue(cache.lookup(client.cacheKey("hello")).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void textFallbackHasNoProvider() {
        ModelAnswer fallback = ModelAnswer.text("...");
        assertEquals("", fallback.providerId());
        assertEquals("", fallback.model());
        assertEquals(ResponseUsage.none(), fallback.usage());
        assertEquals(0, fallback.attempts());
        assertFalse(fallback.fallbackModelUsed());
    }

    private static void assertMetadataCopied(ModelAnswer answer) {
        assertEquals(Duration.ofSeconds(3), answer.cacheTtl());
        assertEquals("stop", answer.finishReason());
        assertEquals(40L, answer.httpNanos());
        assertTrue(answer.usage().reported());
        assertEquals(12, answer.usage().totalTokens());
    }

    private static ChatExchange answered(String model) {
        return new ChatExchange(
                "pong",
                Map.of(),
                Duration.ofSeconds(3),
                "",
                model,
                ResponseUsage.reported(10, 2, 12, null),
                "stop",
                0,
                false,
                40L);
    }

    private static ModelAnswer route(ChatCaller http, GenerationOverrides overrides) {
        return route(http, overrides, config().modelQueue());
    }

    private static ModelAnswer route(ChatCaller http, GenerationOverrides overrides, List<QueueEntryConfig> entries) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RoutingProvider provider = new RoutingProvider(
                    config(), queue(entries), http, executor, Logger.getLogger("meta"), () -> 10_000L);
            return provider.answer("ping", overrides, false).join();
        } finally {
            executor.shutdownNow();
        }
    }

    private static ModelQueue queue(List<QueueEntryConfig> entries) {
        return new ModelQueue(
                entries, 0, 60_000L, 300_000L, null, () -> 10_000L, () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"), Logger.getLogger("meta-queue"));
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.auth-pause-seconds", 300);
        yaml.set("limits.requests-per-minute", 100);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", List.of("sk-one-1111", "sk-two-2222"));
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://api.groq.com/openai/v1");
        yaml.set("providers.groq.api-key", "sk-groq-3333");
        yaml.set("providers.ollama.type", "openai-compatible");
        yaml.set("providers.ollama.url", "http://127.0.0.1:11434/v1");
        yaml.set("providers.ollama.api-key", "local");
        yaml.set("model-queue", List.of(
                Map.of("provider", "openai", "model", "gpt-4o-mini"),
                Map.of("provider", "groq", "model", "llama")));
        return new PluginConfig(yaml);
    }
}
