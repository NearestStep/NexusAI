package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void zeroAndNegativeLimitsMatchSixtyFourAndABurstAccountsForEveryCall() {
        HttpPool.resetQueueFullWarning();
        List<String> warnings = new ArrayList<>();
        Logger logger = capturingLogger("http-limit-burst", warnings);
        HttpPool zero = HttpPool.create(logger, 0, -5);
        HttpPool negative = HttpPool.create(Logger.getLogger("http-limit-negative"), -3, 0);
        HttpPool explicit = HttpPool.create(Logger.getLogger("http-limit-explicit"), 64, 64);
        try {
            assertEquals(64, zero.snapshot().maxInFlight());
            assertEquals(64, zero.snapshot().httpWaitCapacity());
            assertEquals(64, negative.snapshot().maxInFlight());
            assertEquals(64, negative.snapshot().httpWaitCapacity());
            assertEquals(explicit.snapshot().maxInFlight(), zero.snapshot().maxInFlight());
            assertEquals(explicit.snapshot().httpWaitCapacity(), zero.snapshot().httpWaitCapacity());

            Outcome hundred = burst(zero.gate(), 100);
            Outcome explicitHundred = burst(explicit.gate(), 100);
            assertEquals(100, hundred.sent() + hundred.rejected());
            assertEquals(explicitHundred.sent(), hundred.sent());
            assertEquals(explicitHundred.rejected(), hundred.rejected());
            assertEquals(100, hundred.sent());
            assertEquals(0, hundred.rejected());

            HttpPool small = HttpPool.create(Logger.getLogger("http-limit-small"), 4, 4);
            try {
                assertEquals(4, small.snapshot().maxInFlight());
                small.applyLimits(0, -2);
                assertEquals(64, small.snapshot().maxInFlight());
                assertEquals(64, small.snapshot().httpWaitCapacity());
            } finally {
                small.executor().shutdownNow();
            }
            zero.applyLimits(0, -1);
            assertEquals(64, zero.snapshot().maxInFlight());
            assertEquals(64, zero.snapshot().httpWaitCapacity());

            HttpPool.resetQueueFullWarning();
            warnings.clear();
            Outcome overflow = burst(zero.gate(), HttpPool.MAX_IN_FLIGHT + HttpPool.WAIT_QUEUE_CAPACITY + 32);
            int total = HttpPool.MAX_IN_FLIGHT + HttpPool.WAIT_QUEUE_CAPACITY + 32;
            assertEquals(total, overflow.sent() + overflow.rejected());
            assertEquals(HttpPool.MAX_IN_FLIGHT + HttpPool.WAIT_QUEUE_CAPACITY, overflow.sent());
            assertEquals(32, overflow.rejected());
            assertFalse(warnings.isEmpty(), warnings.toString());
            assertTrue(warnings.getFirst().contains(HttpPool.QUEUE_FULL), warnings.toString());
        } finally {
            zero.executor().shutdownNow();
            negative.executor().shutdownNow();
            explicit.executor().shutdownNow();
        }
    }

    @Test
    void workerRejectionWarnsAndIsCounted() throws Exception {
        HttpPool.resetQueueFullWarning();
        List<String> warnings = new ArrayList<>();
        Logger logger = capturingLogger("http-worker-reject", warnings);
        HttpPool pool = HttpPool.create(logger);
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
            int extra = 7;
            int thrown = 0;
            for (int i = 0; i < extra; i++) {
                try {
                    pool.executor().execute(() -> await(hold));
                } catch (RejectedExecutionException rejected) {
                    assertEquals(HttpPool.QUEUE_FULL, rejected.getMessage());
                    thrown++;
                }
            }
            assertEquals(extra, thrown);
            assertEquals(extra, pool.snapshot().workerRejected());
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().contains(HttpPool.QUEUE_FULL), warnings.toString());
        } finally {
            hold.countDown();
            pool.executor().shutdownNow();
        }
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

    private static Outcome burst(HttpGate gate, int total) {
        List<CompletableFuture<String>> holds = new ArrayList<>(total);
        List<CompletableFuture<String>> futures = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            CompletableFuture<String> hold = new CompletableFuture<>();
            holds.add(hold);
            futures.add(gate.schedule(() -> hold));
        }
        for (CompletableFuture<String> hold : holds) {
            hold.complete("ok");
        }
        int sent = 0;
        int rejected = 0;
        for (CompletableFuture<String> future : futures) {
            try {
                assertEquals("ok", future.join());
                sent++;
            } catch (CompletionException ex) {
                assertTrue(HttpPool.isQueueFull(ex), String.valueOf(ex.getCause()));
                rejected++;
            }
        }
        return new Outcome(sent, rejected);
    }

    private static Logger capturingLogger(String name, List<String> lines) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
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

    private record Outcome(int sent, int rejected) {
    }
}
