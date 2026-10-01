package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDiagnosticsTest {

    @Test
    void warningsAreRateLimitedButLastErrorUpdates() {
        AtomicLong clock = new AtomicLong(10_000L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("nexusai-diagnostics-" + clock.get());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
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

        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), clock::get);
        diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429");
        diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429 again");
        assertEquals(1, warnings.size());
        assertTrue(diagnostics.lastError().contains("429 again"));
        assertTrue(warnings.getFirst().contains("rate limit"));
        assertTrue(!warnings.getFirst().contains("paused"));

        clock.addAndGet(30_000L);
        diagnostics.report(AiErrorKind.TIMEOUT, "timed out");
        assertEquals(2, warnings.size());
        assertTrue(diagnostics.lastError().contains("timed out"));
    }

    @Test
    void unauthorizedAndRateLimitMentionPauseOnlyWhenPaused() {
        AiDiagnostics diagnostics = new AiDiagnostics(Logger.getLogger("nexusai-diagnostics-wording"), Duration.ofSeconds(1));
        diagnostics.report(AiErrorKind.BAD_KEY, "HTTP 401", false);
        assertTrue(diagnostics.lastError().contains("invalid or unauthorized"));
        assertTrue(!diagnostics.lastError().contains("paused"));
        diagnostics.report(AiErrorKind.BAD_KEY, "HTTP 403", true);
        assertTrue(diagnostics.lastError().contains("invalid or unauthorized"));
        assertTrue(diagnostics.lastError().contains("paused"));
        diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429", false);
        assertTrue(diagnostics.lastError().contains("rate limit"));
        assertTrue(!diagnostics.lastError().contains("paused"));
    }

    @Test
    void emptyReplyWarningFollowsTheRetryTimeWhileLastErrorDoes() {
        AtomicLong clock = new AtomicLong(20_000L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("nexusai-diagnostics-empty-" + clock.get());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
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

        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), clock::get);
        diagnostics.report(AiErrorKind.EMPTY_REPLY, "Retry after 2026-10-01 10:05:00.");
        diagnostics.report(AiErrorKind.EMPTY_REPLY, "Retry after 2026-10-01 10:05:00.");
        assertEquals(1, warnings.size());
        assertEquals(warnings.getFirst(), diagnostics.lastError());

        diagnostics.report(AiErrorKind.EMPTY_REPLY, "Retry after 2026-10-01 10:20:00.");
        assertEquals(2, warnings.size());
        assertEquals(warnings.getLast(), diagnostics.lastError());
        assertTrue(diagnostics.lastError().contains("10:20:00"));
        assertTrue(warnings.getFirst().contains("10:05:00"));
    }

    @Test
    void oneEmptyReplyDeadlineIsLoggedOnceUnderConcurrency() throws Exception {
        AtomicLong clock = new AtomicLong(50_000L);
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("nexusai-diagnostics-race-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
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

        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), clock::get);
        String first = "Retry after 2026-10-01 11:37:01.";
        reportTogether(diagnostics, AiErrorKind.EMPTY_REPLY, first, 12);
        assertEquals(1, warnings.size());
        assertEquals(warnings.getFirst(), diagnostics.lastError());
        assertTrue(diagnostics.lastError().contains("11:37:01"));

        String second = "Retry after 2026-10-01 12:07:01.";
        reportTogether(diagnostics, AiErrorKind.EMPTY_REPLY, second, 12);
        assertEquals(2, warnings.size());
        assertEquals(warnings.getLast(), diagnostics.lastError());
        assertTrue(warnings.getFirst().contains("11:37:01"));
        assertTrue(warnings.getLast().contains("12:07:01"));

        reportTogether(diagnostics, AiErrorKind.RATE_LIMIT, "HTTP 429", 8);
        assertEquals(3, warnings.size());
        assertTrue(diagnostics.lastError().contains("429"));
    }

    private static void reportTogether(AiDiagnostics diagnostics, AiErrorKind kind, String detail, int threads)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> {
                    await(barrier);
                    diagnostics.report(kind, detail);
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void localLimitIsNotAProviderError() {
        AiDiagnostics diagnostics = new AiDiagnostics(Logger.getLogger("nexusai-diagnostics-local"), Duration.ofSeconds(1));
        diagnostics.report(AiErrorKind.LOCAL_LIMIT, "local");
        assertEquals(null, diagnostics.lastError());
    }
}
