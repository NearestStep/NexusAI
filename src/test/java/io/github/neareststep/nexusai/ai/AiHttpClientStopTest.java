package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plugin disable with AI requests still waiting for the provider. */
class AiHttpClientStopTest {

    private final List<LogRecord> records = new ArrayList<>();

    @Test
    void anOpenRequestIsCancelledAtOnceQuietlyAndNothingRunsLater() throws Exception {
        Controlled provider = new Controlled();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        AiHttpClient client = new AiHttpClient(cache, provider, config(), logger());
        CompletableFuture<String> reply = client.requestAsync("ping");
        CompletableFuture<ModelAnswer> upstream = provider.last;
        assertEquals(1, client.pendingCount());

        long started = System.nanoTime();
        assertEquals(1, client.close());
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // No wait: a reply arriving during disable could not be scheduled to its reader anyway.
        assertTrue(tookMillis < 50, "close took " + tookMillis + " ms");
        assertEquals(0, client.pendingCount());
        assertTrue(upstream.isCancelled());
        assertTrue(reply.isDone());

        assertThrows(CancellationException.class, () -> reply.get(1, TimeUnit.SECONDS));
        // The provider's own future finishing later reaches no handler of this client.
        provider.inner.complete(ModelAnswer.text("late"));
        assertTrue(cache.get(client.cacheKey("ping")).isEmpty());
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()), names());
    }

    @Test
    void aCancelledCommandReplyIsCountedOnceNotAlsoAsASkippedDelivery() {
        io.github.neareststep.nexusai.StopGate.open();
        try {
            Controlled provider = new Controlled();
            AiHttpClient client = new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), provider, config(), logger());
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            client.requestAsync("ping").whenComplete((answer, error) ->
                    io.github.neareststep.nexusai.command.SenderTasks.runReply(
                            null, null, error, () -> delivered.set(true), logger()));
            io.github.neareststep.nexusai.StopGate.close();
            io.github.neareststep.nexusai.StopGate.dropped(client.close());
            assertTrue(!delivered.get());
            String summary = io.github.neareststep.nexusai.StopGate.takeSummary();
            assertTrue(summary.startsWith("Stopped with 1 AI request(s)") && summary.contains(" and 0 reply"), summary);
        } finally {
            io.github.neareststep.nexusai.StopGate.open();
        }
    }

    @Test
    void aReplyFinishedBeforeCloseIsKept() throws Exception {
        Controlled provider = new Controlled();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        AiHttpClient client = new AiHttpClient(cache, provider, config(), logger());
        CompletableFuture<String> reply = client.requestAsync("ping");
        provider.inner.complete(ModelAnswer.text("pong"));
        assertEquals("pong", reply.get(5, TimeUnit.SECONDS));
        assertEquals(0, client.close());
        assertTrue(cache.get(client.cacheKey("ping")).isPresent());
    }

    @Test
    void aReplyArrivingAfterCloseRunsNoHandler() throws Exception {
        Controlled provider = new Controlled();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        AiHttpClient client = new AiHttpClient(cache, provider, config(), logger());
        CompletableFuture<String> reply = client.requestAsync("ping");
        CompletableFuture<ModelAnswer> upstream = provider.last;
        // A provider that does not support cancel: the reply still arrives after close.
        provider.ignoreCancel = true;
        client.close();
        assertTrue(reply.isCompletedExceptionally());
        provider.inner.complete(ModelAnswer.text("late"));
        assertTrue(cache.get(client.cacheKey("ping")).isEmpty());
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()), names());
        assertTrue(upstream.isDone());
    }

    @Test
    void aRequestAfterCloseFailsAtOnceWithoutCallingTheProvider() {
        Controlled provider = new Controlled();
        AiHttpClient client = new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), provider, config(), logger());
        assertEquals(0, client.close());
        CompletableFuture<String> reply = client.requestAsync("ping");
        assertThrows(CancellationException.class, () -> reply.get(1, TimeUnit.SECONDS));
        assertEquals(0, provider.calls.get());
    }

    @Test
    void closeWithNothingOpenReturnsAtOnce() {
        AiHttpClient client = new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), new Controlled(), config(), logger());
        long started = System.nanoTime();
        assertEquals(0, client.close());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
    }

    private String names() {
        return records.stream().map(LogRecord::getMessage).toList().toString();
    }

    private Logger logger() {
        Logger logger = Logger.getLogger("ai-stop-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
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

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "sk-stop-test-1234");
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 1000);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        return new PluginConfig(yaml);
    }

    /** Returns a future the test completes; optionally one whose cancel does nothing. */
    private static final class Controlled implements AiProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile CompletableFuture<ModelAnswer> last;
        volatile CompletableFuture<ModelAnswer> inner;
        volatile boolean ignoreCancel;

        @Override
        public CompletableFuture<String> complete(String prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ModelAnswer> answer(String prompt, GenerationOverrides overrides, boolean ignoreCooldown, CallTrace trace) {
            calls.incrementAndGet();
            inner = new CompletableFuture<>();
            Controlled self = this;
            CompletableFuture<ModelAnswer> outer = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterrupt) {
                    return !self.ignoreCancel && super.cancel(mayInterrupt);
                }
            };
            inner.whenComplete((value, error) -> {
                if (error != null) {
                    outer.completeExceptionally(error);
                } else {
                    outer.complete(value);
                }
            });
            last = outer;
            return outer;
        }
    }
}
