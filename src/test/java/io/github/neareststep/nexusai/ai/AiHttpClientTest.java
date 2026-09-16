package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
