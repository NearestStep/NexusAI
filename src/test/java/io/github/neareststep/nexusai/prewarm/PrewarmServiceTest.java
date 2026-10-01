package io.github.neareststep.nexusai.prewarm;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        String expected = "Welcome, Steve";
        await(() -> expected.equals(seen.get()), 2, TimeUnit.SECONDS);
        assertEquals(expected, seen.get());
        assertEquals("ok", cache.get(client.cacheKey(expected)).orElseThrow());
        assertTrue(client.cacheKey(expected).contains(PlayerInput.KEY_VERSION));
        service.shutdown();
    }

    @Test
    void prewarmRespectsTheSharedRateLimitAndPause() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000L);
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new AiRequestException(AiErrorKind.QUOTA, 402, "HTTP 402", null));
        };
        PluginConfig pluginConfig = config("test-key", List.of("one", "two"));
        RequestGate gate = new RequestGate(new RateLimiter(100, 100), 2_000L, 30_000L, 60_000L, 300_000L, clock::get);
        AiHttpClient client = new AiHttpClient(
                cache,
                provider,
                pluginConfig,
                gate,
                new AiDiagnostics(Logger.getLogger("prewarm-gate"), Duration.ofSeconds(30)),
                Logger.getLogger("prewarm-gate")
        );
        PrewarmService service = new PrewarmService(pluginConfig, cache, client, scheduler, Logger.getLogger("prewarm-gate"));
        service.start();
        await(() -> client.isProviderPaused(), 2, TimeUnit.SECONDS);
        int afterPause = calls.get();
        assertTrue(afterPause >= 1);
        assertTrue(afterPause <= 2);
        service.refreshStale();
        Thread.sleep(40);
        assertEquals(afterPause, calls.get());
        service.shutdown();
    }

    @Test
    void placeholderApiSkipIsLoggedOnlyWhenThePromptHasPapiVars() {
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("nope");
        };
        List<String> infos = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("prewarm-papi");
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue() && record.getMessage() != null) {
                    infos.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            PromptCatalog catalog = PromptCatalog.parse("""
                    t_pre:
                      prompt: "The time is {time}."
                    who:
                      prompt: "Hello {who}"
                      vars:
                        who: "%player_name%"
                    """).catalog();
            PluginConfig pluginConfig = config("test-key", List.of("t_pre", "who"));
            AiHttpClient client = new AiHttpClient(cache, provider, pluginConfig, logger);
            PrewarmService service = new PrewarmService(
                    pluginConfig, cache, client, scheduler, logger, catalog);
            service.start();
            assertEquals(0, calls.get());
            assertFalse(infos.stream().anyMatch(line -> line.contains("t_pre") && line.contains("PlaceholderAPI")));
            assertTrue(infos.stream().anyMatch(line -> line.contains("\"who\"") && line.contains("PlaceholderAPI")));
            service.shutdown();
        } finally {
            logger.removeHandler(handler);
        }
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
