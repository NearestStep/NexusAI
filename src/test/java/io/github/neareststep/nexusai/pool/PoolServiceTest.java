package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PoolServiceTest {

    private AiCache cache;
    private AiPool pool;

    @BeforeEach
    void setUp() {
        assumeTrue(System.getenv("NEXUSAI_API_KEY") == null
                || System.getenv("NEXUSAI_API_KEY").isBlank());
        cache = new AiCache(Duration.ofMinutes(5), 100);
        pool = new AiPool();
    }

    @AfterEach
    void tearDown() {
        // no-op
    }

    private static PluginConfig config(String key, int size, int minThreshold) {
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
        yaml.set("pool.enabled", true);
        yaml.set("pool.max-total-prompts", 10);
        yaml.set("pool.entries", List.of(
                Map.of("prompt", "tip", "size", size, "min-threshold", minThreshold)
        ));
        yaml.set("prewarm.enabled", false);
        yaml.set("prewarm.refresh-before-ttl", 60);
        yaml.set("prewarm.prompts", List.of());
        return new PluginConfig(yaml);
    }

    @Test
    void replenishFillsUpToConfiguredSize() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            int n = calls.incrementAndGet();
            return CompletableFuture.completedFuture("answer-" + n);
        };
        PluginConfig pluginConfig = config("test-key", 3, 1);
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PoolService service = new PoolService(pluginConfig, pool, client, Logger.getLogger("test"));
        service.start();

        await(() -> pool.size("tip") == 3, 2, TimeUnit.SECONDS);
        assertEquals(3, pool.size("tip"));
        assertEquals(3, calls.get());
        assertTrue(cache.get(client.cacheKey("tip")).isEmpty());
        service.shutdown();
    }

    @Test
    void onConsumeTriggersReplenishBelowThreshold() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> CompletableFuture.completedFuture("v" + calls.incrementAndGet());
        PluginConfig pluginConfig = config("test-key", 3, 2);
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PoolService service = new PoolService(pluginConfig, pool, client, Logger.getLogger("test"));
        service.start();
        await(() -> pool.size("tip") == 3, 2, TimeUnit.SECONDS);

        pool.poll("tip");
        pool.poll("tip");
        assertEquals(1, pool.size("tip"));
        service.onConsume("tip");

        await(() -> pool.size("tip") == 3, 2, TimeUnit.SECONDS);
        assertEquals(3, pool.size("tip"));
        service.shutdown();
    }

    @Test
    void parallelReplenishDoesNotDuplicate() throws Exception {
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
                return "unique";
            });
        };

        PluginConfig pluginConfig = config("test-key", 2, 1);
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PoolService service = new PoolService(pluginConfig, pool, client, Logger.getLogger("test"));
        service.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        service.replenish("tip");
        service.replenish("tip");
        release.countDown();

        await(() -> pool.size("tip") == 2, 2, TimeUnit.SECONDS);
        assertEquals(2, calls.get());
        service.shutdown();
    }

    @Test
    void withoutApiKeyDoesNotSpamHttp() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("nope");
        };
        PluginConfig pluginConfig = config("", 3, 1);
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PoolService service = new PoolService(pluginConfig, pool, client, Logger.getLogger("test"));
        service.start();
        service.replenish("tip");
        service.onConsume("tip");
        assertEquals(0, calls.get());
        assertEquals(0, pool.size("tip"));
        service.shutdown();
    }

    private static void await(Condition condition, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.ok(), "condition not met in time");
    }

    @FunctionalInterface
    private interface Condition {
        boolean ok();
    }
}
