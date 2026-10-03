package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPoolTest {

    @Test
    void workerQueueRejectsPastFourThreadsPlusSixtyFour() throws Exception {
        HttpPool pool = HttpPool.create(Logger.getLogger("http-pool-workers"));
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(HttpPool.WORKERS);
        try {
            for (int i = 0; i < HttpPool.WORKERS; i++) {
                pool.executor().execute(() -> {
                    started.countDown();
                    await(hold);
                });
            }
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < HttpPool.WORK_QUEUE_CAPACITY; i++) {
                pool.executor().execute(() -> await(hold));
            }
            RejectedExecutionException rejected = assertThrows(
                    RejectedExecutionException.class,
                    () -> pool.executor().execute(() -> await(hold)));
            assertEquals(HttpPool.QUEUE_FULL, rejected.getMessage());
            HttpPool.Snapshot snapshot = pool.snapshot();
            assertEquals(HttpPool.WORKERS, snapshot.workers());
            assertEquals(HttpPool.WORK_QUEUE_CAPACITY, snapshot.workerQueueCapacity());
            assertEquals(HttpPool.WORK_QUEUE_CAPACITY, snapshot.workerQueued());
            assertEquals(1L, snapshot.workerRejected());
            assertTrue(snapshot.workerQueued() <= snapshot.workerQueueCapacity());
        } finally {
            hold.countDown();
            pool.executor().shutdownNow();
        }
    }

    @Test
    void gateHoldsSixtyFourThenWaitsThenRejects() {
        HttpGate gate = new HttpGate(2, 1, null);
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        CompletableFuture<String> third = new CompletableFuture<>();
        CompletableFuture<String> a = gate.schedule(() -> first);
        CompletableFuture<String> b = gate.schedule(() -> second);
        CompletableFuture<String> waiting = gate.schedule(() -> third);
        CompletableFuture<String> rejected = gate.schedule(() -> CompletableFuture.completedFuture("nope"));

        assertTrue(rejected.isCompletedExceptionally());
        CompletionCheck queueFull = completion(rejected);
        assertEquals(AiErrorKind.LOCAL_LIMIT, queueFull.kind());
        assertEquals(HttpPool.QUEUE_FULL, queueFull.message());
        HttpGate.Snapshot full = gate.snapshot();
        assertEquals(2, full.inFlight());
        assertEquals(1, full.waiting());
        assertEquals(1L, full.rejected());
        assertTrue(full.inFlight() <= full.maxInFlight());
        assertTrue(full.waiting() <= full.waitCapacity());

        first.complete("one");
        assertEquals("one", a.join());
        assertEquals("two-pending", third.isDone() ? "started-early" : "two-pending");
        third.complete("three");
        assertEquals("three", waiting.join());
        second.complete("two");
        assertEquals("two", b.join());
        HttpGate.Snapshot idle = gate.snapshot();
        assertEquals(0, idle.inFlight());
        assertEquals(0, idle.waiting());
        assertEquals(1L, idle.rejected());
    }

    @Test
    void standardGateMatchesTheLoadTestCap() {
        HttpGate gate = HttpGate.standard(null);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<?>[] held = new CompletableFuture<?>[HttpPool.MAX_IN_FLIGHT];
        for (int i = 0; i < HttpPool.MAX_IN_FLIGHT; i++) {
            CompletableFuture<String> pending = new CompletableFuture<>();
            held[i] = pending;
            gate.schedule(() -> {
                started.incrementAndGet();
                return pending;
            });
        }
        assertEquals(HttpPool.MAX_IN_FLIGHT, started.get());
        assertEquals(HttpPool.MAX_IN_FLIGHT, gate.snapshot().inFlight());
        CompletableFuture<String> parked = new CompletableFuture<>();
        gate.schedule(() -> {
            started.incrementAndGet();
            return parked;
        });
        assertEquals(HttpPool.MAX_IN_FLIGHT, started.get());
        assertEquals(1, gate.snapshot().waiting());
        for (CompletableFuture<?> pending : held) {
            pending.complete(null);
        }
        parked.complete("ok");
        assertEquals(HttpPool.MAX_IN_FLIGHT + 1, started.get());
        assertEquals(0, gate.snapshot().inFlight());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static CompletionCheck completion(CompletableFuture<?> future) {
        try {
            future.join();
            throw new AssertionError("expected failure");
        } catch (Throwable thrown) {
            Throwable current = thrown;
            while (current != null) {
                if (current instanceof AiRequestException ai) {
                    return new CompletionCheck(ai.kind(), ai.getMessage());
                }
                current = current.getCause();
            }
            throw thrown;
        }
    }

    private record CompletionCheck(AiErrorKind kind, String message) {
    }
}
