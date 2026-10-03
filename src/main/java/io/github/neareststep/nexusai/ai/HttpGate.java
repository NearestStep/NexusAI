package io.github.neareststep.nexusai.ai;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Bounds how many HTTP calls may be outstanding.
 * A call that does not fit in {@code maxInFlight} waits in a queue of {@code waitCapacity}.
 * Past that, {@link #schedule} completes with {@link HttpPool#QUEUE_FULL} and does not queue the call.
 * The supplier runs only while a slot is held, and it must not block the caller: return a
 * {@code sendAsync} future. The slot is released when that future completes.
 *
 * <p>Load tests read {@link #snapshot()}. The counters do not grow without a bound.
 */
public final class HttpGate {

    private final int maxInFlight;
    private final int waitCapacity;
    private final Logger logger;
    private final Object lock = new Object();
    private final ArrayDeque<Runnable> waiters = new ArrayDeque<>();
    private final AtomicLong rejected = new AtomicLong();
    private int inFlight;
    private int waiting;

    /**
     * A non-positive {@code maxInFlight} is {@link HttpPool#MAX_IN_FLIGHT}.
     * A negative {@code waitCapacity} is {@link HttpPool#WAIT_QUEUE_CAPACITY}.
     * Zero wait capacity is explicit: calls that do not fit fail at once and are not queued.
     */
    public HttpGate(int maxInFlight, int waitCapacity, Logger logger) {
        this.maxInFlight = maxInFlight > 0 ? maxInFlight : HttpPool.MAX_IN_FLIGHT;
        this.waitCapacity = waitCapacity >= 0 ? waitCapacity : HttpPool.WAIT_QUEUE_CAPACITY;
        this.logger = logger;
    }

    public static HttpGate unlimited() {
        return new HttpGate(1_000_000, 1_000_000, null);
    }

    public static HttpGate standard(Logger logger) {
        return new HttpGate(HttpPool.MAX_IN_FLIGHT, HttpPool.WAIT_QUEUE_CAPACITY, logger);
    }

    /**
     * Runs {@code call} when an in-flight slot is free. The returned future fails immediately
     * with {@link HttpPool#QUEUE_FULL} when both the in-flight cap and the wait queue are full.
     */
    public <T> CompletableFuture<T> schedule(Supplier<CompletableFuture<T>> call) {
        Objects.requireNonNull(call, "call");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable job = () -> run(call, result);
        boolean startNow = false;
        synchronized (lock) {
            if (inFlight < maxInFlight) {
                inFlight++;
                startNow = true;
            } else if (waiting < waitCapacity) {
                waiting++;
                waiters.addLast(job);
            } else {
                noteRejection();
                result.completeExceptionally(HttpPool.queueFull(null));
                return result;
            }
        }
        if (startNow) {
            job.run();
        }
        return result;
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(maxInFlight, inFlight, waitCapacity, waiting, rejected.get());
        }
    }

    private <T> void run(Supplier<CompletableFuture<T>> call, CompletableFuture<T> result) {
        CompletableFuture<T> upstream;
        try {
            upstream = call.get();
        } catch (Throwable thrown) {
            release();
            result.completeExceptionally(thrown);
            return;
        }
        if (upstream == null) {
            release();
            result.complete(null);
            return;
        }
        upstream.whenComplete((value, error) -> {
            release();
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        });
    }

    private void release() {
        Runnable next = null;
        synchronized (lock) {
            if (!waiters.isEmpty()) {
                next = waiters.removeFirst();
                waiting--;
            } else {
                inFlight--;
            }
        }
        if (next != null) {
            next.run();
        }
    }

    private void noteRejection() {
        rejected.incrementAndGet();
        HttpPool.warnQueueFull(logger);
    }

    public record Snapshot(int maxInFlight, int inFlight, int waitCapacity, int waiting, long rejected) {
    }
}
