package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenLedgerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final OffsetDateTime UPDATED = OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void slicesFollowOriginAndAPlayerIsCountedOnlyForTheQuotaOrigins() {
        TokenLedger ledger = ledger(LocalDate.of(2026, 10, 7));
        record(ledger, ResponseUsage.reported(100, 20, 120, null), RequestOrigin.PLACEHOLDER, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(1, 1, 2, null), RequestOrigin.TALK, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(1, 1, 2, null), RequestOrigin.TALK_GREETING, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(1, 1, 2, null), RequestOrigin.SUMMARY, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(3, 1, 4, null), RequestOrigin.API, PLAYER, "Shop", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(5, 0, 5, null), RequestOrigin.MODERATION, PLAYER, "nexusai", "openai", "1|openai|mod", false);
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.POOL, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.PREWARM, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.TEST, PLAYER, "nexusai", "openai", "0|openai|gpt-4o-mini", false);
        record(ledger, ResponseUsage.reported(2, 0, 2, null), RequestOrigin.PLACEHOLDER, null, "nexusai", "groq", "groq|llama", true);

        TokenLedger.Snapshot snap = ledger.snapshot();
        assertEquals(9L + 1L, snap.server().requests());
        assertEquals(120L + 2L + 2L + 2L + 4L + 5L + 1L + 1L + 1L + 2L, snap.server().total());
        assertEquals(0L, snap.server().estimated());
        assertEquals(138L, snap.providers().get("openai").total());
        assertEquals(2L, snap.providers().get("groq").total());
        assertEquals(133L, snap.rows().get("0|openai|gpt-4o-mini").total());
        assertEquals(5L, snap.rows().get("1|openai|mod").total());
        assertFalse(snap.rows().containsKey("groq|llama"));
        assertEquals(2L, snap.fallback().get("groq|llama").total());
        assertEquals(122L, snap.origins().get("placeholder").total());
        assertEquals(2L, snap.origins().get("talk").total());
        assertEquals(2L, snap.origins().get("talk_greeting").total());
        assertEquals(2L, snap.origins().get("summary").total());
        assertEquals(4L, snap.origins().get("api").total());
        assertEquals(5L, snap.origins().get("moderation").total());
        assertEquals(1L, snap.origins().get("pool").total());
        assertEquals(1L, snap.origins().get("prewarm").total());
        assertEquals(1L, snap.origins().get("test").total());
        assertEquals(136L, snap.consumers().get("nexusai").total());
        assertEquals(4L, snap.consumers().get("Shop").total());
        assertEquals(120L + 2L + 2L + 2L + 4L, snap.players().get(PLAYER.toString()).total());
        assertEquals(1L, snap.players().size());
    }

    @Test
    void estimateCountsTheWholeTotalAndIgnoreKeepsTheRequestWithZeroTokens() {
        TokenLedger estimated = ledger(LocalDate.of(2026, 10, 7));
        record(estimated, ResponseUsage.estimate(8, 4), RequestOrigin.API, PLAYER, "Shop", "openai", "0|openai|m", false);
        TokenLedger.Counts estimate = estimated.snapshot().server();
        assertEquals(1L, estimate.requests());
        assertEquals(2L, estimate.prompt());
        assertEquals(1L, estimate.completion());
        assertEquals(3L, estimate.total());
        assertEquals(3L, estimate.estimated());

        TokenLedger ignored = ledger(LocalDate.of(2026, 10, 7));
        ignored.missingUsage(MissingUsage.IGNORE);
        record(ignored, ResponseUsage.estimate(8, 4), RequestOrigin.API, PLAYER, "Shop", "openai", "0|openai|m", false);
        TokenLedger.Counts zeros = ignored.snapshot().server();
        assertEquals(1L, zeros.requests());
        assertEquals(0L, zeros.prompt());
        assertEquals(0L, zeros.completion());
        assertEquals(0L, zeros.total());
        assertEquals(0L, zeros.estimated());

        TokenLedger reported = ledger(LocalDate.of(2026, 10, 7));
        reported.missingUsage(MissingUsage.IGNORE);
        record(reported, ResponseUsage.reported(100, 20, 50, null), RequestOrigin.API, null, "Shop", "openai", "0|openai|m", false);
        TokenLedger.Counts kept = reported.snapshot().server();
        assertEquals(120L, kept.total());
        assertEquals(0L, kept.estimated());
    }

    @Test
    void aResponseWithoutUsageIsNotCounted() {
        TokenLedger ledger = ledger(LocalDate.of(2026, 10, 7));
        record(ledger, ResponseUsage.none(), RequestOrigin.PLACEHOLDER, PLAYER, "nexusai", "openai", "0|openai|m", false);
        record(ledger, null, RequestOrigin.PLACEHOLDER, PLAYER, "nexusai", "openai", "0|openai|m", false);
        assertEquals(0L, ledger.snapshot().server().requests());
        assertEquals(0L, ledger.mutations());
    }

    @Test
    void eightThreadsAddEveryCharge() throws Exception {
        TokenLedger ledger = ledger(LocalDate.of(2026, 10, 7));
        int threads = 8;
        int each = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "token-ledger-race");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        ready.await();
                        for (int n = 0; n < each; n++) {
                            record(ledger, ResponseUsage.reported(1, 1, 2, null), RequestOrigin.PLACEHOLDER, PLAYER, "nexusai", "openai", "0|openai|gpt", false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        TokenLedger.Counts server = ledger.snapshot().server();
        assertEquals(threads * (long) each, server.requests());
        assertEquals(threads * (long) each, server.prompt());
        assertEquals(threads * (long) each, server.completion());
        assertEquals(threads * (long) each * 2L, server.total());
        assertEquals(server.requests(), ledger.snapshot().rows().get("0|openai|gpt").requests());
        assertEquals(server.requests(), ledger.snapshot().players().get(PLAYER.toString()).requests());
    }

    @Test
    void aLaterDayRollsOnceAndASkippedDayIsASingleHistoryLine() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 1));
        TokenLedger ledger = new TokenLedger(today::get, Logger.getLogger("token-day"));
        record(ledger, ResponseUsage.reported(4, 1, 5, null), RequestOrigin.SUMMARY, PLAYER, "nexusai", "openai", "0|openai|m", false);
        record(ledger, ResponseUsage.reported(1, 1, 2, null), RequestOrigin.SUMMARY, PLAYER, "nexusai", "openai", "0|openai|m", false);
        today.set(LocalDate.of(2026, 10, 4));
        record(ledger, ResponseUsage.reported(3, 0, 3, null), RequestOrigin.SUMMARY, PLAYER, "nexusai", "openai", "0|openai|m", false);

        TokenLedger.Snapshot snap = ledger.snapshot();
        assertEquals(LocalDate.of(2026, 10, 4), snap.day());
        assertEquals(1L, snap.server().requests());
        assertEquals(3L, snap.server().total());
        assertEquals(1L, snap.rows().get("0|openai|m").requests());
        assertEquals(1, snap.history().size());
        TokenLedger.DayTotal previous = snap.history().getFirst();
        assertEquals(LocalDate.of(2026, 10, 1), previous.day());
        assertEquals(2L, previous.requests());
        assertEquals(7L, previous.total());
        assertEquals(1L, snap.players().get(PLAYER.toString()).requests());
    }

    @Test
    void historyKeepsThirtyDaysNewestFirst() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 1, 1));
        TokenLedger ledger = new TokenLedger(today::get, Logger.getLogger("token-history"));
        for (int i = 0; i < 35; i++) {
            record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.TEST, null, "nexusai", "openai", "0|openai|m", false);
            today.set(today.get().plusDays(1));
            ledger.catchUp();
        }
        TokenLedger.Snapshot snap = ledger.snapshot();
        assertEquals(LocalDate.of(2026, 2, 5), snap.day());
        assertEquals(0L, snap.server().requests());
        assertEquals(30, snap.history().size());
        assertEquals(LocalDate.of(2026, 2, 4), snap.history().getFirst().day());
        assertEquals(LocalDate.of(2026, 1, 6), snap.history().get(29).day());
        assertNull(snap.players().get(PLAYER.toString()));
    }

    @Test
    void aClockMovedBackwardKeepsCountersAndWarnsOnce() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 7));
        List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("token-clock-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
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
        TokenLedger ledger = new TokenLedger(today::get, logger);
        record(ledger, ResponseUsage.reported(2, 0, 2, null), RequestOrigin.TALK, PLAYER, "nexusai", "openai", "0|openai|m", false);
        today.set(LocalDate.of(2026, 10, 6));
        record(ledger, ResponseUsage.reported(3, 0, 3, null), RequestOrigin.TALK, PLAYER, "nexusai", "openai", "0|openai|m", false);
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.TALK, PLAYER, "nexusai", "openai", "0|openai|m", false);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("2026-10-07"));
        assertTrue(warnings.getFirst().contains("2026-10-06"));
        assertEquals(3L, ledger.snapshot().server().requests());
        assertEquals(6L, ledger.snapshot().server().total());
        assertTrue(ledger.snapshot().history().isEmpty());

        today.set(LocalDate.of(2026, 10, 8));
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.TALK, PLAYER, "nexusai", "openai", "0|openai|m", false);
        assertEquals(1, warnings.size());
        assertEquals(1L, ledger.snapshot().server().requests());
        assertEquals(1, ledger.snapshot().history().size());
        assertEquals(3L, ledger.snapshot().history().getFirst().requests());
    }

    @Test
    void eightThreadsSurviveADayChange() throws Exception {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 1));
        TokenLedger ledger = new TokenLedger(today::get, Logger.getLogger("token-roll-race"));
        int threads = 8;
        int each = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "token-roll-race");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch started = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        started.countDown();
                        release.await();
                        for (int n = 0; n < each; n++) {
                            record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.API, PLAYER, "Shop", "openai", "0|openai|m", false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(started.await(10, TimeUnit.SECONDS));
            release.countDown();
            today.set(LocalDate.of(2026, 10, 3));
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        TokenLedger.Snapshot snap = ledger.snapshot();
        long historical = snap.history().stream().mapToLong(TokenLedger.DayTotal::requests).sum();
        assertEquals(threads * (long) each, historical + snap.server().requests());
        assertTrue(snap.history().size() <= 1);
        if (!snap.history().isEmpty()) {
            assertEquals(LocalDate.of(2026, 10, 1), snap.history().getFirst().day());
        }
        assertEquals(LocalDate.of(2026, 10, 3), snap.day());
    }

    @Test
    void renderedKeysStaySortedAndRoundTrip() {
        TokenLedger ledger = ledger(LocalDate.of(2026, 10, 7));
        record(ledger, ResponseUsage.reported(1, 0, 1, null), RequestOrigin.PLACEHOLDER, PLAYER, "zeta", "zeta", "9|zeta|m", false);
        record(ledger, ResponseUsage.reported(2, 0, 2, null), RequestOrigin.PLACEHOLDER, null, "alpha", "alpha", "0|alpha|m", false);
        String yaml = TokenUsageFile.render(ledger.snapshot(), UPDATED, List.of());
        int providers = yaml.indexOf("providers:\n");
        int alpha = yaml.indexOf("\"alpha\":", providers);
        int zeta = yaml.indexOf("\"zeta\":", providers);
        assertTrue(alpha > providers && alpha < zeta, yaml);
        assertTrue(yaml.indexOf("history:") > yaml.indexOf("players:"), yaml);
        assertTrue(yaml.contains("requests: 2\n"), yaml);
        assertFalse(yaml.contains("prompt text"));
        TokenLedger.Snapshot parsed = TokenUsageFile.parse(yaml);
        assertEquals(ledger.snapshot().server(), parsed.server());
        assertEquals(ledger.snapshot().providers(), parsed.providers());
        assertEquals(ledger.snapshot().rows(), parsed.rows());
        assertEquals(ledger.snapshot().consumers(), parsed.consumers());
        assertEquals(LocalDate.of(2026, 10, 7), parsed.day());
        assertTrue(parsed.history().isEmpty());
    }

    @Test
    void aSecretInASliceKeyIsMaskedAndNewlinesCannotBreakTheLine() {
        TokenLedger ledger = ledger(LocalDate.of(2026, 10, 7));
        String canary = "sk-CanaryKey0123456789Ab";
        CallTrace trace = CallTrace.start(RequestOrigin.API, canary, PLAYER, "prompt", "label");
        ledger.record(ResponseUsage.reported(1, 0, 1, null), trace, "open\nai", "0|row\rwith|model", false);
        String yaml = TokenUsageFile.render(ledger.snapshot(), UPDATED, List.of(canary));
        assertFalse(yaml.contains(canary), yaml);
        assertFalse(yaml.contains("\n" + "ai"), yaml);
        assertTrue(yaml.contains("****89Ab") || yaml.contains("****"), yaml);
        TokenLedger.Snapshot parsed = TokenUsageFile.parse(yaml);
        assertEquals(1L, parsed.server().requests());
        assertTrue(parsed.providers().containsKey("open_ai"), parsed.providers().keySet().toString());
        assertTrue(parsed.rows().keySet().stream().anyMatch(key -> key.contains("_")), parsed.rows().keySet().toString());
    }

    @Test
    void playerAttributionIgnoresModerationPoolPrewarmAndTest() {
        assertEquals(PLAYER, TokenLedger.player(RequestOrigin.PLACEHOLDER, PLAYER));
        assertEquals(PLAYER, TokenLedger.player(RequestOrigin.TALK, PLAYER));
        assertEquals(PLAYER, TokenLedger.player(RequestOrigin.TALK_GREETING, PLAYER));
        assertEquals(PLAYER, TokenLedger.player(RequestOrigin.SUMMARY, PLAYER));
        assertEquals(PLAYER, TokenLedger.player(RequestOrigin.API, PLAYER));
        assertNull(TokenLedger.player(RequestOrigin.PLACEHOLDER, null));
        assertNull(TokenLedger.player(RequestOrigin.MODERATION, PLAYER));
        assertNull(TokenLedger.player(RequestOrigin.POOL, PLAYER));
        assertNull(TokenLedger.player(RequestOrigin.PREWARM, PLAYER));
        assertNull(TokenLedger.player(RequestOrigin.TEST, PLAYER));
    }

    private static TokenLedger ledger(LocalDate day) {
        return new TokenLedger(() -> day, Logger.getLogger("token-ledger"));
    }

    private static void record(
            TokenLedger ledger,
            ResponseUsage usage,
            RequestOrigin origin,
            UUID player,
            String consumer,
            String provider,
            String storageKey,
            boolean fallback
    ) {
        ledger.record(usage, CallTrace.start(origin, consumer, player, "prompt", ""), provider, storageKey, fallback);
    }
}
