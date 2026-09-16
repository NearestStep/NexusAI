package io.github.neareststep.nexusai.prewarm;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PrewarmServiceTest {

    private AiCache cache;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        assumeTrue(System.getenv("NEXUSAI_API_KEY") == null
                || System.getenv("NEXUSAI_API_KEY").isBlank());
        cache = new AiCache(Duration.ofSeconds(5), 100);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static PluginConfig config(String key, List<String> prompts) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", key);
        yaml.set("api.connect-timeout", 5);
        yaml.set("api.read-timeout", 30);
        yaml.set("cache.ttl", 5);
        yaml.set("cache.max-size", 1000);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        yaml.set("pool.enabled", false);
        yaml.set("pool.max-total-prompts", 10);
        yaml.set("pool.entries", List.of());
        yaml.set("prewarm.enabled", true);
        yaml.set("prewarm.refresh-before-ttl", 60);
        yaml.set("prewarm.prompts", prompts);
        return new PluginConfig(yaml);
    }

    @Test
    void startPutsAnswersIntoCache() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("warmed:" + prompt);
        };
        PluginConfig pluginConfig = config("test-key", List.of("hello"));
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PrewarmService service = new PrewarmService(
                pluginConfig, cache, client, scheduler, Logger.getLogger("test"));
        service.start();

        await(() -> cache.get(client.cacheKey("hello")).isPresent(), 2, TimeUnit.SECONDS);
        assertEquals("warmed:hello", cache.get(client.cacheKey("hello")).orElseThrow());
        assertEquals(1, calls.get());
        service.shutdown();
    }

    @Test
    void refreshSkipsWhenFresh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("v" + calls.get());
        };
        PluginConfig pluginConfig = config("test-key", List.of("tip"));
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PrewarmService service = new PrewarmService(
                pluginConfig, cache, client, scheduler, Logger.getLogger("test"));
        service.start();
        await(() -> cache.isFresh(client.cacheKey("tip")), 2, TimeUnit.SECONDS);
        int afterStart = calls.get();

        service.refreshStale();
        Thread.sleep(50);
        assertEquals(afterStart, calls.get());
        service.shutdown();
    }

    @Test
    void warmForPlayerSubstitutesName() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        AiProvider provider = prompt -> {
            seen.set(prompt);
            return CompletableFuture.completedFuture("ok");
        };
        PluginConfig pluginConfig = config("test-key", List.of("Welcome, {player}"));
        AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, Logger.getLogger("test"));
        PrewarmService service = new PrewarmService(
                pluginConfig, cache, client, scheduler, Logger.getLogger("test"));
        service.start();
        assertNull(seen.get());

        service.warmForPlayer("Steve");

        await(() -> "Welcome, Steve".equals(seen.get()), 2, TimeUnit.SECONDS);
        assertEquals("Welcome, Steve", seen.get());
        assertEquals("ok", cache.get(client.cacheKey("Welcome, Steve")).orElseThrow());
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
