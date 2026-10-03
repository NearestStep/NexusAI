package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusContextProvider;
import io.github.neareststep.nexusai.prompt.PromptContext;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Collects context off the main thread. Each provider runs on {@code nexusai-context-N}.
 * A timeout or an exception skips that provider. The returned future does not complete exceptionally.
 * A full worker queue skips the provider and increments {@link #rejected()}.
 */
public final class ContextService {

    public static final int THREADS = 2;
    public static final int QUEUE_CAPACITY = 256;

    private static final DateTimeFormatter SUSPEND_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContextRegistry registry;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Health> health = new ConcurrentHashMap<>();
    private final Set<String> truncationLogged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> lastErrorLog = new ConcurrentHashMap<>();
    private final AtomicInteger rejected = new AtomicInteger();

    private volatile ContextSettings settings;
    private volatile long errorLogCooldownMillis;

    public ContextService(
            ContextRegistry registry,
            ContextSettings settings,
            ExecutorService workers,
            ScheduledExecutorService scheduler,
            Logger logger,
            LongSupplier clock,
            int errorLogCooldownSeconds
    ) {
        this.registry = registry;
        this.settings = settings == null ? ContextSettings.defaults() : settings;
        this.workers = workers;
        this.scheduler = scheduler;
        this.logger = logger;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.errorLogCooldownMillis = Math.max(1, errorLogCooldownSeconds) * 1000L;
    }

    public static ExecutorService newWorkerPool(int threads, int queueCapacity) {
        int size = Math.max(1, threads);
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexusai-context-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size,
                size,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        // The load harness counts live nexusai-context-* threads. Idle workers still count:
        // a scenario that never calls a provider must see the configured pool, not zero.
        executor.prestartAllCoreThreads();
        return executor;
    }

    /** Reloads limits and clears suspension, timeout counters, and trim notices. The registry stays. */
    public void apply(ContextSettings settings, int errorLogCooldownSeconds) {
        this.settings = settings == null ? ContextSettings.defaults() : settings;
        this.errorLogCooldownMillis = Math.max(1, errorLogCooldownSeconds) * 1000L;
        health.clear();
        truncationLogged.clear();
        lastErrorLog.clear();
        rejected.set(0);
    }

    public ContextSettings settings() {
        return settings;
    }

    public int rejected() {
        return rejected.get();
    }

    /**
     * Wrapped block, or empty when no provider contributed. Never completes exceptionally.
     */
    public CompletableFuture<String> collect(ContextRequest request, PromptContext selection) {
        ContextSettings current = settings;
        if (!current.enabled() || request == null || selection == null || !selection.active() || registry == null) {
            return CompletableFuture.completedFuture("");
        }
        List<ContextRegistry.Entry> chosen = new ArrayList<>();
        for (ContextRegistry.Entry entry : registry.active()) {
            if (selection.includes(ContextRegistry.safeId(entry.provider()))) {
                chosen.add(entry);
            }
        }
        if (chosen.isEmpty()) {
            return CompletableFuture.completedFuture("");
        }
        List<CompletableFuture<Contribution>> parts = new ArrayList<>();
        for (ContextRegistry.Entry entry : chosen) {
            parts.add(one(request, entry, current));
        }
        CompletableFuture<String> done = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable assemble = () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                done.complete(assemble(parts, current));
            } catch (RuntimeException ex) {
                done.complete("");
            }
        };
        CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> assemble.run());
        if (scheduler != null) {
            scheduler.schedule(assemble, current.totalTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
        return done;
    }

    public List<StatusRow> status(long nowMillis) {
        return status(nowMillis, ZoneId.systemDefault());
    }

    public List<StatusRow> status(long nowMillis, ZoneId zone) {
        List<StatusRow> rows = new ArrayList<>();
        if (registry == null) {
            return rows;
        }
        for (ContextRegistry.Entry entry : registry.active()) {
            String id = ContextRegistry.safeId(entry.provider());
            Health row = health.get(id);
            boolean suspended = false;
            long until = 0L;
            int timeouts = 0;
            if (row != null) {
                synchronized (row) {
                    suspended = row.suspended(nowMillis);
                    until = row.suspendedUntil;
                    timeouts = row.timeouts;
                }
            }
            rows.add(new StatusRow(
                    id,
                    entry.pluginName() == null || entry.pluginName().isBlank() ? "unknown" : entry.pluginName(),
                    ContextRegistry.safePriority(entry.provider()),
                    effectiveTimeoutMillis(entry.provider()),
                    suspended,
                    until,
                    timeouts,
                    zone == null ? ZoneId.systemDefault() : zone
            ));
        }
        return List.copyOf(rows);
    }

    public static String formatLine(StatusRow row) {
        if (row == null) {
            return "";
        }
        String state = row.suspended()
                ? "suspended until " + SUSPEND_CLOCK.format(Instant.ofEpochMilli(row.suspendedUntilMillis()).atZone(row.zone()))
                : "ok";
        return row.id() + " (" + row.pluginName() + ") prio " + row.priority()
                + ", " + row.timeoutMillis() + "ms, " + state + ", timeouts " + row.timeouts();
    }

    public int effectiveTimeoutMillis(NexusContextProvider provider) {
        long requested = 100;
        if (provider != null) {
            try {
                Duration timeout = provider.timeout();
                if (timeout != null) {
                    requested = timeout.toMillis();
                }
            } catch (RuntimeException ignored) {
                requested = 100;
            }
        }
        if (requested < 1) {
            requested = 1;
        }
        return (int) Math.min(requested, settings.maxProviderTimeoutMillis());
    }

    private CompletableFuture<Contribution> one(ContextRequest request, ContextRegistry.Entry entry, ContextSettings current) {
        CompletableFuture<Contribution> result = new CompletableFuture<>();
        String id = ContextRegistry.safeId(entry.provider());
        int priority = ContextRegistry.safePriority(entry.provider());
        long now = clock.getAsLong();
        Health row = health.computeIfAbsent(id, ignored -> new Health());
        synchronized (row) {
            if (row.suspended(now)) {
                result.complete(Contribution.empty(id, priority));
                return result;
            }
        }
        int timeoutMs = effectiveTimeoutMillis(entry.provider());
        AtomicBoolean settled = new AtomicBoolean();
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> timerBox = new AtomicReference<>();
        Future<?> task;
        try {
            task = workers.submit(() -> {
                started.set(true);
                try {
                    CompletableFuture<String> provided = entry.provider().provide(request);
                    if (provided == null) {
                        finish(result, timerBox.get(), settled, id, priority, null, null, current);
                        return;
                    }
                    provided.whenComplete((value, error) ->
                            finish(result, timerBox.get(), settled, id, priority, value, error, current));
                } catch (Throwable thrown) {
                    finish(result, timerBox.get(), settled, id, priority, null, thrown, current);
                } finally {
                    // A provider that restores the interrupt status must not poison the next task.
                    Thread.interrupted();
                }
            });
        } catch (RejectedExecutionException ex) {
            rejected.incrementAndGet();
            if (settled.compareAndSet(false, true)) {
                result.complete(Contribution.empty(id, priority));
            }
            return result;
        }
        if (scheduler != null) {
            Future<?> running = task;
            ScheduledFuture<?> timer = scheduler.schedule(() -> {
                boolean began = started.get();
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                running.cancel(true);
                if (began) {
                    // The call itself overran. A task still waiting for a worker is a full pool,
                    // same as a rejected submission, and is not a strike against this provider.
                    recordFailure(id, null, true, current);
                }
                result.complete(Contribution.empty(id, priority));
            }, timeoutMs, TimeUnit.MILLISECONDS);
            timerBox.set(timer);
            if (settled.get()) {
                timer.cancel(false);
            }
        }
        return result;
    }

    private void finish(
            CompletableFuture<Contribution> result,
            ScheduledFuture<?> timer,
            AtomicBoolean settled,
            String id,
            int priority,
            String value,
            Throwable error,
            ContextSettings current
    ) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        cancel(timer);
        if (error != null) {
            recordFailure(id, error, false, current);
            result.complete(Contribution.empty(id, priority));
            return;
        }
        recordSuccess(id);
        String text;
        try {
            text = ContextSanitizer.value(value, current.maxCharsPerProvider(), this::noteTruncation, id);
        } catch (RuntimeException ex) {
            recordFailure(id, ex, false, current);
            result.complete(Contribution.empty(id, priority));
            return;
        }
        result.complete(new Contribution(id, priority, text));
    }

    private String assemble(List<CompletableFuture<Contribution>> parts, ContextSettings current) {
        List<ContextSanitizer.Line> lines = new ArrayList<>();
        for (CompletableFuture<Contribution> part : parts) {
            if (!part.isDone() || part.isCompletedExceptionally()) {
                continue;
            }
            Contribution contribution = part.getNow(null);
            if (contribution == null || contribution.text() == null || contribution.text().isEmpty()) {
                continue;
            }
            lines.add(new ContextSanitizer.Line(contribution.id(), contribution.priority(), contribution.text()));
        }
        return ContextSanitizer.block(lines, current.maxChars());
    }

    private void recordSuccess(String id) {
        Health row = health.computeIfAbsent(id, ignored -> new Health());
        synchronized (row) {
            if (!row.suspended(clock.getAsLong())) {
                row.consecutive = 0;
            }
        }
    }

    private void recordFailure(String id, Throwable error, boolean timeout, ContextSettings current) {
        Health row = health.computeIfAbsent(id, ignored -> new Health());
        boolean suspendNow = false;
        long until = 0L;
        synchronized (row) {
            long now = clock.getAsLong();
            if (row.suspended(now)) {
                return;
            }
            row.timeouts++;
            row.consecutive++;
            if (row.consecutive >= current.suspendAfterTimeouts()) {
                row.consecutive = 0;
                row.suspendedUntil = now + current.suspendSeconds() * 1000L;
                until = row.suspendedUntil;
                suspendNow = true;
            }
        }
        if (!timeout) {
            logFailure(id, error);
        }
        if (suspendNow && logger != null) {
            String when = SUSPEND_CLOCK.format(Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()));
            logger.warning("Context provider '" + id + "' suspended until " + when + ".");
        }
    }

    private void logFailure(String id, Throwable error) {
        if (logger == null || error == null) {
            return;
        }
        long now = clock.getAsLong();
        Long previous = lastErrorLog.get(id);
        if (previous != null && now - previous < errorLogCooldownMillis) {
            return;
        }
        lastErrorLog.put(id, now);
        logger.warning("Context provider '" + id + "' failed (" + error.getClass().getSimpleName() + ") and was skipped.");
    }

    private void noteTruncation(String id) {
        if (id == null || logger == null) {
            return;
        }
        if (truncationLogged.add(id)) {
            logger.info("Context provider '" + id + "' was trimmed to the per-provider character limit.");
        }
    }

    private static void cancel(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private record Contribution(String id, int priority, String text) {
        static Contribution empty(String id, int priority) {
            return new Contribution(id, priority, "");
        }
    }

    public record StatusRow(
            String id,
            String pluginName,
            int priority,
            int timeoutMillis,
            boolean suspended,
            long suspendedUntilMillis,
            int timeouts,
            ZoneId zone
    ) {
        public String format() {
            return formatLine(this);
        }
    }

    private static final class Health {
        private int consecutive;
        private int timeouts;
        private long suspendedUntil;

        private boolean suspended(long now) {
            return now < suspendedUntil;
        }
    }
}
