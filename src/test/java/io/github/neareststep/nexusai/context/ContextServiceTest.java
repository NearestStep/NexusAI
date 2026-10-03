package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusContextProvider;
import io.github.neareststep.nexusai.prompt.PromptContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextServiceTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final List<String> lines = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private ContextRegistry registry;
    private ContextService service;
    private Logger logger;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexusai-scheduler-test");
            thread.setDaemon(true);
            return thread;
        });
        workers = ContextService.newWorkerPool(ContextService.THREADS, ContextService.QUEUE_CAPACITY);
        logger = logger();
        registry = new ContextRegistry(logger);
        service = service(workers, settings(80, 400, 5, 60, 200, 600));
    }

    @AfterEach
    void tearDown() {
        if (workers != null) {
            workers.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void provideRunsOffTheCallerAndInParallel() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicInteger onCaller = new AtomicInteger();
        AtomicInteger inside = new AtomicInteger();
        registry.add("Plug", counting("fast", 10, onCaller, caller, inside, "alpha"));
        registry.add("Plug", counting("slow", 20, onCaller, caller, inside, "beta"));

        String block = service.collect(request(), PromptContext.all()).get(2, TimeUnit.SECONDS);

        assertEquals(0, onCaller.get());
        assertEquals(2, inside.get());
        assertTrue(block.contains("fast: alpha"));
        assertTrue(block.contains("slow: beta"));
        assertTrue(block.indexOf("fast: alpha") < block.indexOf("slow: beta"));
        assertTrue(block.contains("§§§ PLAYER INPUT §§§"));
    }

    @Test
    void blockingProvideDoesNotHoldTheCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        registry.add("SlowPlug", new NexusContextProvider() {
            @Override
            public String id() {
                return "slow";
            }

            @Override
            public Duration timeout() {
                return Duration.ofMillis(40);
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                calls.incrementAndGet();
                worker.set(Thread.currentThread());
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture("late-value");
            }
        });
        ContextService tight = service(workers, settings(40, 200, 5, 60, 200, 600));
        try {
            long started = System.nanoTime();
            String block = tight.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(block.isEmpty(), block);
            assertTrue(millis < 1000, "waited " + millis + "ms");
            assertEquals(1, calls.get());
            assertTrue(worker.get().getName().startsWith("nexusai-context-"));
            assertFalse(lines.stream().anyMatch(line -> line.contains("late-value")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void fiveTimeoutsSuspendTheProviderUntilTheClockAdvances() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        registry.add("SlowPlug", new NexusContextProvider() {
            @Override
            public String id() {
                return "slow";
            }

            @Override
            public Duration timeout() {
                return Duration.ofMillis(30);
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                calls.incrementAndGet();
                return new CompletableFuture<>();
            }
        });
        ContextService tight = service(workers, settings(40, 200, 5, 60, 200, 600));
        for (int i = 0; i < 5; i++) {
            assertEquals("", tight.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS));
        }
        assertEquals(5, calls.get());
        ContextService.StatusRow row = tight.status(clock.get(), ZoneId.of("UTC")).getFirst();
        assertTrue(row.suspended(), row.format());
        assertEquals(5, row.timeouts());
        assertTrue(row.format().contains("suspended until "));
        assertEquals("", tight.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS));
        assertEquals(5, calls.get());

        clock.addAndGet(60_000L);
        assertFalse(tight.status(clock.get(), ZoneId.of("UTC")).getFirst().suspended());
        tight.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS);
        assertEquals(6, calls.get());
        assertEquals(1, lines.stream().filter(line -> line.contains("suspended until")).count());
    }

    @Test
    void totalTimeoutKeepsTheFastProviderAndDropsTheSlowOne() throws Exception {
        registry.add("Plug", fixed("fast", 1, Duration.ofMillis(500), "ready"));
        registry.add("Plug", new NexusContextProvider() {
            @Override
            public String id() {
                return "late";
            }

            @Override
            public int priority() {
                return 2;
            }

            @Override
            public Duration timeout() {
                return Duration.ofMillis(500);
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                return new CompletableFuture<>();
            }
        });
        ContextService tight = service(workers, settings(2_000, 300, 5, 60, 200, 600));
        String block = tight.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS);
        assertTrue(block.contains("fast: ready"));
        assertFalse(block.contains("late"));
    }

    @Test
    void exceptionDoesNotFailTheCollection() throws Exception {
        registry.add("Plug", new NexusContextProvider() {
            @Override
            public String id() {
                return "boom";
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                throw new IllegalStateException("balance 999 secret");
            }
        });
        registry.add("Plug", fixed("ok", 2, Duration.ofMillis(100), "coins"));
        String block = service.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS);
        assertTrue(block.contains("ok: coins"));
        assertFalse(block.contains("boom"));
        assertFalse(block.contains("999"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("boom") && line.contains("IllegalStateException")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("999") || line.contains("secret")));
    }

    @Test
    void fullQueueSkipsTheProvider() throws Exception {
        ThreadPoolExecutor tiny = (ThreadPoolExecutor) ContextService.newWorkerPool(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        try {
            tiny.execute(() -> {
                started.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            tiny.execute(() -> {
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            AtomicInteger calls = new AtomicInteger();
            registry.add("Plug", new NexusContextProvider() {
                @Override
                public String id() {
                    return "queued";
                }

                @Override
                public CompletableFuture<String> provide(ContextRequest request) {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture("nope");
                }
            });
            ContextService small = service(tiny, settings(200, 300, 5, 60, 200, 600));
            String block = small.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS);
            assertTrue(block.isEmpty());
            assertEquals(0, calls.get());
            assertTrue(small.rejected() >= 1);
        } finally {
            hold.countDown();
            tiny.shutdownNow();
        }
    }

    @Test
    void disabledContextDoesNotCallProviders() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        registry.add("Plug", new NexusContextProvider() {
            @Override
            public String id() {
                return "economy";
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture("12k");
            }
        });
        ContextService off = service(workers, new ContextSettings(false, 200, 300, 200, 600, 30, 5, 60));
        assertEquals("", off.collect(request(), PromptContext.all()).get(1, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
    }

    private ContextService service(ExecutorService pool, ContextSettings settings) {
        return new ContextService(registry, settings, pool, scheduler, logger, clock::get, 30);
    }

    private Logger logger() {
        Logger created = Logger.getLogger("context-service-" + UUID.randomUUID());
        created.setUseParentHandlers(false);
        created.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return created;
    }

    private static ContextSettings settings(
            int providerTimeout,
            int totalTimeout,
            int suspendAfter,
            int suspendSeconds,
            int perProvider,
            int totalChars
    ) {
        return new ContextSettings(true, providerTimeout, totalTimeout, perProvider, totalChars, 30, suspendAfter, suspendSeconds);
    }

    private static ContextRequest request() {
        return new ContextRequest(UUID.randomUUID(), "Steve", "world", "shop_tip", ContextRequest.Purpose.PLACEHOLDER);
    }

    private static NexusContextProvider counting(
            String id,
            int priority,
            AtomicInteger onCaller,
            Thread caller,
            AtomicInteger inside,
            String value
    ) {
        return new NexusContextProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                if (Thread.currentThread() == caller) {
                    onCaller.incrementAndGet();
                }
                inside.incrementAndGet();
                long deadline = System.nanoTime() + 1_000_000_000L;
                while (inside.get() < 2 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                return CompletableFuture.completedFuture(value);
            }
        };
    }

    private static NexusContextProvider fixed(String id, int priority, Duration timeout, String value) {
        return new NexusContextProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                return CompletableFuture.completedFuture(value);
            }
        };
    }
}
