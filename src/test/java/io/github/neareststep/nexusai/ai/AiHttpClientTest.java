package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiHttpClientTest {

    private PluginConfig configWithKey;
    private PluginConfig configWithoutKey;
    private AiCache cache;

    @BeforeEach
    void setUp() {
        configWithKey = config("test-key");
        configWithoutKey = config("");
        cache = new AiCache(Duration.ofMinutes(5), 100);
    }

    private static PluginConfig config(String key) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", key);
        yaml.set("api.connect-timeout", 5);
        yaml.set("api.read-timeout", 30);
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 1000);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        return new PluginConfig(yaml);
    }

    @Test
    void cacheHitDoesNotCallProvider() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("nope");
        };
        AiHttpClient client = new AiHttpClient(cache, provider, configWithKey, Logger.getLogger("test"));
        cache.put(client.cacheKey("hello"), "cached");

        assertEquals("cached", client.requestAsync("hello").join());
        assertEquals(0, calls.get());
    }

    @Test
    void withoutApiKeyDoesNotCallProvider() {
        if (System.getenv("NEXUSAI_API_KEY") != null && !System.getenv("NEXUSAI_API_KEY").isBlank()) {
            return;
        }
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("nope");
        };
        AiHttpClient client = new AiHttpClient(cache, provider, configWithoutKey, Logger.getLogger("test"));

        CompletableFuture<String> future = client.requestAsync("hello");
        assertTrue(future.isCompletedExceptionally());
        assertEquals(0, calls.get());
    }

    @Test
    void generateFreshBypassesCache() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("fresh-" + calls.get());
        };
        AiHttpClient client = new AiHttpClient(cache, provider, configWithKey, Logger.getLogger("test"));
        cache.put(client.cacheKey("hello"), "cached");

        assertEquals("fresh-1", client.generateFreshAsync("hello").join());
        assertEquals("cached", cache.get(client.cacheKey("hello")).orElseThrow());
        assertEquals(1, calls.get());
    }

    @Test
    void parallelRequestsShareSingleInFlightCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            started.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "answer";
            });
        };

        AiHttpClient client = new AiHttpClient(cache, provider, configWithKey, Logger.getLogger("test"));
        CompletableFuture<String> first = client.requestAsync("same");
        assertTrue(started.await(2, TimeUnit.SECONDS));
        CompletableFuture<String> second = client.requestAsync("same");

        release.countDown();
        assertEquals("answer", first.get(2, TimeUnit.SECONDS));
        assertEquals("answer", second.get(2, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertEquals("answer", cache.get(client.cacheKey("same")).orElseThrow());
    }

    @Test
    void cacheHitDoesNotSpendRateLimit() {
        RateLimiter limiter = new RateLimiter(1, 100);
        AiHttpClient client = client(configWithKey, limiter, new AtomicLong(1_000L), prompt -> {
            throw new AssertionError("provider should not be called");
        });
        cache.put(client.cacheKey("hello"), "cached");
        assertEquals("cached", client.requestAsync("hello", UUID.randomUUID()).join());
        assertEquals("cached", client.requestAsync("hello", UUID.randomUUID()).join());
    }

    @Test
    void freshAndCachedCallsShareTheServerBudget() {
        AtomicInteger calls = new AtomicInteger();
        RateLimiter limiter = new RateLimiter(2, 100);
        UUID player = UUID.randomUUID();
        AiHttpClient client = client(configWithKey, limiter, new AtomicLong(1_000L), prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("ok-" + prompt);
        });
        assertEquals("ok-a", client.requestAsync("a", player).join());
        assertEquals("ok-b", client.generateFreshAsync("b").join());
        assertTrue(client.requestAsync("c", player).isCompletedExceptionally());
        assertTrue(client.generateFreshAsync("d").isCompletedExceptionally());
        assertEquals(2, calls.get());
    }

    @Test
    void providerPauseBlocksPoolAndPrewarmStyleCallsUntilItExpires() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(5_000L);
        AiHttpClient client = client(configWithKey, new RateLimiter(100, 100), clock, prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429", null));
        });
        assertTrue(client.generateFreshAsync("pool").isCompletedExceptionally());
        assertEquals(1, calls.get());
        assertTrue(client.isProviderPaused());
        assertTrue(client.requestAsync("prewarm").isCompletedExceptionally());
        assertEquals(1, calls.get());

        clock.addAndGet(60_000L);
        assertFalse(client.isProviderPaused());
    }

    @Test
    void testAsyncBypassesPauseWithoutClearingIt() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(5_000L);
        AiProvider provider = prompt -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return CompletableFuture.failedFuture(new AiRequestException(AiErrorKind.BAD_KEY, 401, "HTTP 401", null));
            }
            return CompletableFuture.completedFuture("pong");
        };
        AiHttpClient client = client(configWithKey, new RateLimiter(100, 100), clock, provider);
        assertTrue(client.generateFreshAsync("pool").isCompletedExceptionally());
        assertTrue(client.isProviderPaused());
        assertEquals("pong", client.testAsync("probe").join());
        assertTrue(client.isProviderPaused());
        assertTrue(client.lastErrorText().contains("API key"));
        clock.addAndGet(300_000L);
        assertFalse(client.isProviderPaused());
    }

    @Test
    void testAsyncAsksTheProviderToIgnoreCooldown() {
        AtomicBoolean ignoreCooldown = new AtomicBoolean();
        AiProvider provider = new AiProvider() {
            @Override
            public CompletableFuture<String> complete(String prompt) {
                return CompletableFuture.completedFuture("pong");
            }

            @Override
            public CompletableFuture<String> complete(String prompt, GenerationOverrides overrides, boolean probe) {
                ignoreCooldown.set(probe);
                return CompletableFuture.completedFuture("pong");
            }
        };
        AiHttpClient client = client(configWithKey, new RateLimiter(100, 100), new AtomicLong(1_000L), provider);
        assertEquals("pong", client.requestAsync("live").join());
        assertFalse(ignoreCooldown.get());
        assertEquals("pong", client.testAsync("probe").join());
        assertTrue(ignoreCooldown.get());
    }

    @Test
    void failedTestDoesNotExtendProviderPause() {
        AtomicLong clock = new AtomicLong(10_000L);
        AiProvider provider = prompt -> CompletableFuture.failedFuture(
                new AiRequestException(AiErrorKind.BAD_KEY, 401, "HTTP 401", null));
        AiHttpClient client = client(configWithKey, new RateLimiter(100, 100), clock, provider);
        assertTrue(client.generateFreshAsync("live").isCompletedExceptionally());
        clock.addAndGet(5_000L);
        long remaining = client.pauseRemainingSeconds();
        assertTrue(client.testAsync("probe").isCompletedExceptionally());
        assertEquals(remaining, client.pauseRemainingSeconds());
        assertTrue(client.isProviderPaused());
    }

    @Test
    void localhostWithoutKeyStillCallsProvider() {
        if (System.getenv("NEXUSAI_API_KEY") != null && !System.getenv("NEXUSAI_API_KEY").isBlank()) {
            return;
        }
        AtomicInteger calls = new AtomicInteger();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "llama3.2");
        yaml.set("api.base-url", "http://127.0.0.1:11434/v1");
        yaml.set("api.key", "");
        PluginConfig local = new PluginConfig(yaml);
        AiHttpClient client = new AiHttpClient(cache, prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("local");
        }, local, Logger.getLogger("test"));
        assertEquals("local", client.generateFreshAsync("hi").join());
        assertEquals(1, calls.get());
    }

    private AiHttpClient client(PluginConfig config, RateLimiter limiter, AtomicLong clock, AiProvider provider) {
        RequestGate gate = new RequestGate(limiter, 2_000L, 30_000L, 60_000L, 300_000L, clock::get);
        AiDiagnostics diagnostics = new AiDiagnostics(Logger.getLogger("test-client"), Duration.ofSeconds(30), clock::get);
        return new AiHttpClient(cache, provider, config, gate, diagnostics, Logger.getLogger("test-client"));
    }
}
