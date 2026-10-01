package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmptyReplyBackoffTest {

    private static final DateTimeFormatter RETRY_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long FIVE_MINUTES = 5L * 60_000L;
    private static final long FIFTEEN_MINUTES = 15L * 60_000L;

    @Test
    void cacheDoesNotReRequestAColourOnlyReplyDuringBackoff() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(5_000L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = quietLogger("empty-cache", warnings);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        AiHttpClient client = client(cache, clock, logger, prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("&c§l");
        });

        assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        assertEquals(1, calls.get());
        assertTrue(client.lastErrorText().startsWith(PlayerInput.EMPTY_REPLY));
        assertTrue(client.lastErrorText().contains("Retry after " + retryAt(clock.get() + FIVE_MINUTES)));
        assertFalse(client.lastErrorText().contains("missing choices"));
        assertTrue(cache.get(client.cacheKey("blank")).isEmpty());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("empty after removing colour codes")
                && line.contains("Retry after ")));
        assertFalse(warnings.stream().anyMatch(line -> line.contains("missing choices")));

        for (int i = 0; i < 30; i++) {
            assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        }
        assertEquals(1, calls.get());

        clock.addAndGet(FIVE_MINUTES - 1L);
        for (int i = 0; i < 10; i++) {
            assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        }
        assertEquals(1, calls.get());

        clock.addAndGet(1L);
        assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        assertEquals(2, calls.get());
        assertTrue(client.lastErrorText().contains("Retry after " + retryAt(clock.get() + FIFTEEN_MINUTES)));

        clock.addAndGet(11L * 60_000L);
        for (int i = 0; i < 10; i++) {
            assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        }
        assertEquals(2, calls.get());

        client.resetBackoff();
        assertTrue(client.requestAsync("blank").isCompletedExceptionally());
        assertEquals(3, calls.get());
        assertTrue(client.lastErrorText().contains("Retry after " + retryAt(clock.get() + FIVE_MINUTES)));
    }

    @Test
    void successfulReplyResetsTheEmptyReplyLadder() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean text = new AtomicBoolean(false);
        AtomicLong clock = new AtomicLong(5_000L);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        AiHttpClient client = client(cache, clock, Logger.getLogger("empty-reset"), prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(text.get() ? "Hello" : "&c§l");
        });

        assertTrue(client.generateFreshAsync("blank").isCompletedExceptionally());
        clock.addAndGet(FIVE_MINUTES);
        text.set(true);
        assertEquals("Hello", client.generateFreshAsync("blank").join());
        assertEquals(2, calls.get());
        assertFalse(client.isAdmissionBlocked("blank"));

        text.set(false);
        assertTrue(client.generateFreshAsync("blank").isCompletedExceptionally());
        assertEquals(3, calls.get());
        assertTrue(client.lastErrorText().contains("Retry after " + retryAt(clock.get() + FIVE_MINUTES)));
        clock.addAndGet(FIVE_MINUTES - 1L);
        assertTrue(client.generateFreshAsync("blank").isCompletedExceptionally());
        assertEquals(3, calls.get());
        clock.addAndGet(1L);
        assertTrue(client.generateFreshAsync("blank").isCompletedExceptionally());
        assertEquals(4, calls.get());
    }

    @Test
    void poolDoesNotRefillAColourOnlyReplyInATightLoop() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(5_000L);
        Logger logger = quietLogger("empty-pool", new CopyOnWriteArrayList<>());
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        AiHttpClient client = client(cache, clock, logger, prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("&x&f&f&0&0&0&0 §k");
        });
        AiPool pool = new AiPool();
        List<Long> delays = new CopyOnWriteArrayList<>();
        AtomicReference<Runnable> pending = new AtomicReference<>();
        BiConsumer<Long, Runnable> retry = (delay, task) -> {
            delays.add(delay);
            pending.set(task);
        };
        PoolService service = new PoolService(poolConfig(), pool, client, logger, null, retry);
        service.start();

        assertEquals(0, pool.size("blank"));
        assertEquals(1, calls.get());
        assertEquals(List.of(FIVE_MINUTES + 25L), delays);
        for (int i = 0; i < 20; i++) {
            service.replenish("blank");
            service.onConsume("blank");
        }
        assertEquals(1, calls.get());
        assertEquals(1, delays.size());

        pending.get().run();
        assertEquals(1, calls.get());
        assertEquals(2, delays.size());
        assertEquals(FIVE_MINUTES + 25L, delays.get(1));

        clock.addAndGet(FIVE_MINUTES - 1L);
        pending.get().run();
        assertEquals(1, calls.get());

        clock.addAndGet(1L);
        pending.get().run();
        assertEquals(2, calls.get());
        assertEquals(FIFTEEN_MINUTES + 25L, delays.get(delays.size() - 1));

        clock.addAndGet(11L * 60_000L);
        pending.get().run();
        service.replenish("blank");
        service.onConsume("blank");
        assertEquals(2, calls.get());

        client.resetBackoff();
        service.replenish("blank");
        assertEquals(3, calls.get());
        assertEquals(0, pool.size("blank"));
        service.shutdown();
    }

    @Test
    void aReplyThatStillHasTextIsCached() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(5_000L);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        AiHttpClient client = client(cache, clock, Logger.getLogger("empty-text"), prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("&cHello");
        });
        assertEquals("&cHello", client.requestAsync("greet").join());
        assertEquals("Hello", client.requestAsync("greet").join());
        assertEquals(1, calls.get());
        assertFalse(client.isAdmissionBlocked("greet"));
    }

    private static String retryAt(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(RETRY_CLOCK);
    }

    private static AiHttpClient client(AiCache cache, AtomicLong clock, Logger logger, AiProvider provider) {
        RequestGate gate = new RequestGate(
                new RateLimiter(10_000, 10_000),
                2_000L,
                120_000L,
                60_000L,
                300_000L,
                clock::get);
        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), clock::get);
        return new AiHttpClient(cache, provider, keyedConfig(), gate, diagnostics, logger);
    }

    private static PluginConfig keyedConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("cache.ttl", 300);
        yaml.set("fallback", "...");
        yaml.set("pool.enabled", false);
        return new PluginConfig(yaml);
    }

    private static PluginConfig poolConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("fallback", "BLANK-FALLBACK");
        yaml.set("pool.enabled", true);
        yaml.set("pool.entries", List.of(Map.of("prompt", "blank", "size", 3, "min-threshold", 1)));
        return new PluginConfig(yaml);
    }

    private static Logger quietLogger(String name, List<String> warnings) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }
}
