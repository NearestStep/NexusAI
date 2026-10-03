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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        AtomicInteger inProvide = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch arrived = new CountDownLatch(2);
        registry.add("Plug", counting("fast", 10, onCaller, caller, inside, arrived, inProvide, peak, "alpha"));
        registry.add("Plug", counting("slow", 20, onCaller, caller, inside, arrived, inProvide, peak, "beta"));

        // The default cap is 80ms. A timed spin inside provide() races that cap under load.
        ContextService room = service(workers, settings(2_000, 2_000, 5, 60, 200, 600));
        String block = room.collect(request(), PromptContext.all()).get(2, TimeUnit.SECONDS);

        assertEquals(0, onCaller.get());
        assertEquals(2, inside.get());
        assertEquals(2, peak.get());
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
    void workerPoolPrestartsTheConfiguredDaemonThreads() throws Exception {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) ContextService.newWorkerPool(ContextService.THREADS, 8);
        try {
            assertEquals(ContextService.THREADS, pool.getPoolSize());
            assertEquals(ContextService.THREADS, pool.getMaximumPoolSize());
            CountDownLatch done = new CountDownLatch(ContextService.THREADS);
            AtomicInteger daemons = new AtomicInteger();
            AtomicInteger named = new AtomicInteger();
            for (int i = 0; i < ContextService.THREADS; i++) {
                pool.execute(() -> {
                    Thread current = Thread.currentThread();
                    if (current.isDaemon() && current.getName().startsWith("nexusai-context-")) {
                        daemons.incrementAndGet();
                    }
                    if (current.getName().startsWith("nexusai-context-")) {
                        named.incrementAndGet();
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(ContextService.THREADS, daemons.get());
            assertEquals(ContextService.THREADS, named.get());
            assertEquals(ContextService.THREADS, pool.getPoolSize());
        } finally {
            pool.shutdownNow();
        }
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
    void timeoutBeforeStartDoesNotSuspendTheWaitingProvider() throws Exception {
        ThreadPoolExecutor one = (ThreadPoolExecutor) ContextService.newWorkerPool(1, 8);
        ManualScheduler manual = new ManualScheduler();
        AtomicInteger quickCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            registry.add("Plug", new NexusContextProvider() {
                @Override
                public String id() {
                    return "blocker";
                }

                @Override
                public int priority() {
                    return 1;
                }

                @Override
                public Duration timeout() {
                    return Duration.ofMillis(40);
                }

                @Override
                public CompletableFuture<String> provide(ContextRequest request) {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                    return CompletableFuture.completedFuture("blocked");
                }
            });
            registry.add("Plug", new NexusContextProvider() {
                @Override
                public String id() {
                    return "quick";
                }

                @Override
                public int priority() {
                    return 50;
                }

                @Override
                public Duration timeout() {
                    return Duration.ofMillis(40);
                }

                @Override
                public CompletableFuture<String> provide(ContextRequest request) {
                    quickCalls.incrementAndGet();
                    return CompletableFuture.completedFuture("ready");
                }
            });
            ContextService tight = new ContextService(
                    registry,
                    settings(40, 200, 5, 60, 200, 600),
                    one,
                    manual,
                    logger,
                    clock::get,
                    30
            );
            CompletableFuture<String> pending = tight.collect(request(), PromptContext.all());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            // 0 blocker timeout, 1 queued quick timeout, 2 collection budget.
            // Quick's timer has to win while the blocker still owns the only worker.
            // A live scheduler frees that worker from the blocker callback first, and quick
            // can still be inside its own budget.
            assertEquals(3, manual.size());
            manual.run(1);
            manual.run(0);
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            String blocked = pending.get(1, TimeUnit.SECONDS);
            assertFalse(blocked.contains("quick"), blocked);
            assertEquals(0, quickCalls.get());
            ContextService.StatusRow quick = row(tight, "quick");
            ContextService.StatusRow blocker = row(tight, "blocker");
            assertEquals(0, quick.timeouts(), quick.format());
            assertFalse(quick.suspended(), quick.format());
            assertTrue(blocker.timeouts() >= 1, blocker.format());

            String later = tight.collect(request(), PromptContext.of(List.of("quick"))).get(1, TimeUnit.SECONDS);
            assertTrue(later.contains("quick: ready"), later);
            assertEquals(1, quickCalls.get());
            assertEquals(0, row(tight, "quick").timeouts());
        } finally {
            one.shutdownNow();
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

    private ContextService.StatusRow row(ContextService service, String id) {
        return service.status(clock.get(), ZoneId.of("UTC")).stream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElseThrow();
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
            CountDownLatch arrived,
            AtomicInteger inProvide,
            AtomicInteger peak,
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
            public Duration timeout() {
                return Duration.ofSeconds(2);
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                if (Thread.currentThread() == caller) {
                    onCaller.incrementAndGet();
                }
                inside.incrementAndGet();
                int now = inProvide.incrementAndGet();
                peak.updateAndGet(seen -> Math.max(seen, now));
                arrived.countDown();
                try {
                    arrived.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    inProvide.decrementAndGet();
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

    /**
     * Records scheduler tasks and runs them only when the test says so.
     * Provider timeouts stay ordered relative to the worker, instead of racing a live delay.
     */
    private static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private final List<ManualTask> tasks = new ArrayList<>();
        private boolean shutdown;

        int size() {
            synchronized (tasks) {
                return tasks.size();
            }
        }

        void run(int index) {
            ManualTask task;
            synchronized (tasks) {
                task = tasks.get(index);
            }
            task.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualTask task = new ManualTask(command);
            synchronized (tasks) {
                tasks.add(task);
            }
            return task;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object>, Runnable {
        private final Runnable command;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean ran = new AtomicBoolean();

        private ManualTask(Runnable command) {
            this.command = command;
        }

        @Override
        public void run() {
            if (cancelled.get() || !ran.compareAndSet(false, true)) {
                return;
            }
            command.run();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (ran.get()) {
                return false;
            }
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            ran.set(true);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return ran.get();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
