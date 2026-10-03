package io.github.neareststep.nexusai.ai;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Four {@code nexusai-http-N} workers plus a separate cap on outstanding HTTP calls.
 * Workers only start work. The HTTP call itself is {@code HttpClient.sendAsync}, so a slow
 * provider does not pin a worker for the whole round trip.
 *
 * <p>The worker queue holds at most {@link #WORK_QUEUE_CAPACITY} tasks. HTTP calls hold at most
 * {@link #MAX_IN_FLIGHT} slots, with {@link #WAIT_QUEUE_CAPACITY} more waiting for a slot.
 * Anything beyond that fails with {@link #QUEUE_FULL} and is not queued. Placeholders already
 * showed fallback; the background future fails the same way. A rejected call is never dropped
 * quietly: the future fails and one warning is written per 30 seconds.
 *
 * <p>{@link #snapshot()} is the load-test hook ({@code NexusAI.getHttpPool()}). It reports
 * worker queue depth, in-flight calls, waiters, and how many submissions were rejected.
 */
public final class HttpPool {

    public static final int WORKERS = 4;
    public static final int WORK_QUEUE_CAPACITY = 64;
    public static final int MAX_IN_FLIGHT = 64;
    public static final int WAIT_QUEUE_CAPACITY = 64;
    public static final String QUEUE_FULL = "HTTP queue is full";
    private static final long QUEUE_FULL_WARN_MILLIS = 30_000L;
    private static final AtomicLong queueFullWarnedAt = new AtomicLong();

    private final ThreadPoolExecutor workers;
    private final Logger logger;
    private final AtomicLong workerRejected;
    private volatile HttpGate gate;

    private HttpPool(ThreadPoolExecutor workers, HttpGate gate, AtomicLong workerRejected, Logger logger) {
        this.workers = workers;
        this.gate = gate;
        this.workerRejected = workerRejected;
        this.logger = logger;
    }

    public static HttpPool create(Logger logger) {
        return create(logger, MAX_IN_FLIGHT, WAIT_QUEUE_CAPACITY);
    }

    public static HttpPool create(Logger logger, int maxInFlight, int waitCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexusai-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        AtomicLong rejected = new AtomicLong();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                factory,
                (runnable, pool) -> {
                    rejected.incrementAndGet();
                    if (pool.isShutdown()) {
                        throw new RejectedExecutionException("HTTP executor is shut down");
                    }
                    warnQueueFull(logger);
                    throw new RejectedExecutionException(QUEUE_FULL);
                });
        executor.prestartAllCoreThreads();
        int inFlight = positiveOrDefault(maxInFlight, MAX_IN_FLIGHT);
        int waiting = positiveOrDefault(waitCapacity, WAIT_QUEUE_CAPACITY);
        return new HttpPool(executor, new HttpGate(inFlight, waiting, logger), rejected, logger);
    }

    /**
     * Points later calls at a new gate. Calls already holding the previous gate finish on it.
     * The four worker threads stay. A value that is not positive is the matching default
     * ({@link #MAX_IN_FLIGHT} or {@link #WAIT_QUEUE_CAPACITY}), the same replacement
     * {@code PluginConfig} applies to {@code http.max-in-flight} and {@code http.queue-size}.
     */
    public void applyLimits(int maxInFlight, int waitCapacity) {
        int inFlight = positiveOrDefault(maxInFlight, MAX_IN_FLIGHT);
        int waiting = positiveOrDefault(waitCapacity, WAIT_QUEUE_CAPACITY);
        HttpGate current = gate;
        HttpGate.Snapshot snapshot = current.snapshot();
        if (snapshot.maxInFlight() == inFlight && snapshot.waitCapacity() == waiting) {
            return;
        }
        gate = new HttpGate(inFlight, waiting, logger);
    }

    public ExecutorService executor() {
        return workers;
    }

    public HttpGate gate() {
        return gate;
    }

    public Snapshot snapshot() {
        HttpGate.Snapshot http = gate.snapshot();
        return new Snapshot(
                WORKERS,
                WORK_QUEUE_CAPACITY,
                workers.getQueue().size(),
                workers.getActiveCount(),
                workerRejected.get(),
                http.maxInFlight(),
                http.inFlight(),
                http.waitCapacity(),
                http.waiting(),
                http.rejected());
    }

    /**
     * {@code 0} and negative numbers are not a smaller cap. They are the documented default.
     */
    static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    /**
     * One warning per 30 seconds, shared by the worker queue and {@link HttpGate}.
     * Placeholders have already returned fallback. The failed future is the rejection.
     */
    public static void warnQueueFull(Logger logger) {
        if (logger == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = queueFullWarnedAt.get();
        if (now - previous < QUEUE_FULL_WARN_MILLIS || !queueFullWarnedAt.compareAndSet(previous, now)) {
            return;
        }
        logger.warning(QUEUE_FULL
                + ". Further requests are rejected until a slot frees; placeholders use fallback.");
    }

    /** Test hook so a later case can observe a fresh warning. */
    static void resetQueueFullWarning() {
        queueFullWarnedAt.set(0L);
    }

    public static AiRequestException queueFull(Throwable cause) {
        return new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, QUEUE_FULL, cause);
    }

    public static boolean isQueueFull(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AiRequestException ai
                    && ai.kind() == AiErrorKind.LOCAL_LIMIT
                    && QUEUE_FULL.equals(ai.getMessage())) {
                return true;
            }
            if (current instanceof RejectedExecutionException && QUEUE_FULL.equals(current.getMessage())) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    /**
     * @param workerQueued tasks sitting in the four-thread queue, never above {@code workerQueueCapacity}
     * @param httpWaiting calls waiting for an in-flight slot, never above {@code httpWaitCapacity}
     * @param workerRejected submissions refused because the worker queue was full
     * @param httpRejected calls refused because in-flight and the wait queue were both full
     */
    public record Snapshot(
            int workers,
            int workerQueueCapacity,
            int workerQueued,
            int workerActive,
            long workerRejected,
            int maxInFlight,
            int inFlight,
            int httpWaitCapacity,
            int httpWaiting,
            long httpRejected
    ) {
    }
}
