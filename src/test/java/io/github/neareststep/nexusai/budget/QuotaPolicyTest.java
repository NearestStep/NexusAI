package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.config.SecretMask;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaPolicyTest {

    @Test
    void sequentialSpendAllowsOneOvershootThenRejects() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 200L, 0L, Map.of(), Map.of()));
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        assertTrue(spend(fixture, trace, 120).allowed());
        assertEquals(120L, fixture.ledger.snapshot().server().total());
        assertTrue(spend(fixture, trace, 120).allowed());
        assertEquals(240L, fixture.ledger.snapshot().server().total());
        QuotaPolicy.Decision third = fixture.policy.tryReserve(charge(trace, 120));
        assertFalse(third.allowed());
        assertTrue(third.message().contains("Token quota reached: server"));
        assertEquals(1, fixture.logs.size());
        assertFalse(fixture.policy.tryReserve(charge(trace, 1)).allowed());
        assertEquals(1, fixture.logs.size());
    }

    @Test
    void releaseOnCancelOrFailureFreesTheReservation() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 100L, 0L, Map.of(), Map.of()));
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        QuotaPolicy.Decision first = fixture.policy.tryReserve(charge(trace, 100));
        assertTrue(first.allowed());
        assertFalse(fixture.policy.tryReserve(charge(trace, 1)).allowed());
        first.hold().releaseWith(fixture.policy);
        first.hold().releaseWith(fixture.policy);
        QuotaPolicy.Decision again = fixture.policy.tryReserve(charge(trace, 100));
        assertTrue(again.allowed());
        again.hold().releaseWith(fixture.policy);
        fixture.ledger.record(ResponseUsage.reported(1, 1, 1, null), trace, "openai", "", false);
        assertTrue(fixture.policy.tryReserve(charge(trace, 100)).allowed());
    }

    @Test
    void parallelReservationsAdmitOnlyWhatFits() throws Exception {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 100L, 0L, Map.of(), Map.of()));
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger allowed = new AtomicInteger();
        List<QuotaPolicy.Hold> holds = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    QuotaPolicy.Decision decision = fixture.policy.tryReserve(charge(trace, 50));
                    if (decision.allowed()) {
                        allowed.incrementAndGet();
                        holds.add(decision.hold());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, allowed.get());
        assertFalse(fixture.policy.tryReserve(charge(trace, 50)).allowed());
        holds.getFirst().releaseWith(fixture.policy);
        assertTrue(fixture.policy.tryReserve(charge(trace, 50)).allowed());
    }

    @Test
    void releaseAfterMidnightDoesNotTouchTheNewDay() {
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 8));
        Fixture fixture = fixture(today);
        fixture.policy.apply(new QuotaSettings(true, 200L, 0L, Map.of(), Map.of()));
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        QuotaPolicy.Decision yesterday = fixture.policy.tryReserve(charge(trace, 50));
        assertTrue(yesterday.allowed());
        today.set(today.get().plusDays(1));
        QuotaPolicy.Decision todayHold = fixture.policy.tryReserve(charge(trace, 180));
        assertTrue(todayHold.allowed());
        yesterday.hold().releaseWith(fixture.policy);
        assertEquals(0, fixture.logs.size());
        assertTrue(fixture.policy.tryReserve(charge(trace, 30)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(trace, 30)).allowed());
        assertEquals(1, fixture.logs.size());
        assertFalse(fixture.policy.tryReserve(charge(trace, 200)).allowed());
        assertEquals(1, fixture.logs.size());
    }

    @Test
    void mostGenerousGroupWinsPerFieldAndZeroIsUnlimited() {
        QuotaSettings settings = new QuotaSettings(true, 0L, 20L, Map.of(
                "small", new QuotaSettings.GroupLimit(10L, 1L),
                "large", new QuotaSettings.GroupLimit(100L, 4L),
                "open", new QuotaSettings.GroupLimit(0L, 2L)
        ), Map.of());
        QuotaPolicy.Membership both = QuotaPolicy.Membership.resolve(settings, List.of("small", "large"));
        assertTrue(both.grouped());
        assertEquals(100L, both.tokensPerDay());
        assertEquals(4L, both.requestsPerDay());
        QuotaPolicy.Membership open = QuotaPolicy.Membership.resolve(settings, List.of("large", "open"));
        assertEquals(0L, open.tokensPerDay());
        assertEquals(4L, open.requestsPerDay());
        QuotaPolicy.Membership missing = QuotaPolicy.Membership.resolve(settings, List.of("nope"));
        assertFalse(missing.grouped());
        assertEquals(20L, missing.tokensPerDay());

        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(settings);
        UUID player = UUID.randomUUID();
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, player, "", "");
        QuotaPolicy.Decision unlimited = fixture.policy.tryReserve(charge(trace, 1_000_000L, open));
        assertTrue(unlimited.allowed());
        unlimited.hold().releaseWith(fixture.policy);
        QuotaPolicy.Decision capped = fixture.policy.tryReserve(charge(trace, 100L, both));
        assertTrue(capped.allowed());
        assertFalse(fixture.policy.tryReserve(charge(trace, 1L, both)).allowed());
        capped.hold().releaseWith(fixture.policy);
        UUID other = UUID.randomUUID();
        CallTrace requests = CallTrace.start(RequestOrigin.PLACEHOLDER, other, "", "");
        QuotaPolicy.Membership oneRequest = QuotaPolicy.Membership.group(0L, 1L);
        assertTrue(fixture.policy.tryReserve(charge(requests, 1L, oneRequest)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(requests, 1L, oneRequest)).allowed());
    }

    @Test
    void freshGroupReplacesThePlayerDayLimit() {
        AtomicLong clock = new AtomicLong(1_000L);
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8), clock);
        fixture.policy.apply(new QuotaSettings(true, 0L, 0L, Map.of(
                "vip", new QuotaSettings.GroupLimit(0L, 5L)
        ), Map.of()));
        UUID player = UUID.randomUUID();
        assertFalse(fixture.policy.replacesPlayerDay(player));
        fixture.policy.remember(player, List.of("vip"));
        assertTrue(fixture.policy.replacesPlayerDay(player));
        fixture.policy.remember(player, List.of());
        assertFalse(fixture.policy.replacesPlayerDay(player));
        fixture.policy.remember(player, List.of("vip"));
        clock.addAndGet(60_001L);
        assertFalse(fixture.policy.replacesPlayerDay(player));
        fixture.policy.remember(player, List.of("vip"));
        fixture.policy.forget(player);
        assertFalse(fixture.policy.replacesPlayerDay(player));
    }

    @Test
    void defaultConsumerIsPerPluginAndANamedRowWins() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 0L, 0L, Map.of(), Map.of(
                "default", new QuotaSettings.ConsumerLimit(100L, 1L),
                "Quests", new QuotaSettings.ConsumerLimit(10L, 0L)
        )));
        CallTrace quests = CallTrace.start(RequestOrigin.API, "Quests", null, "", "");
        CallTrace shop = CallTrace.start(RequestOrigin.API, "Shop", null, "", "");
        CallTrace other = CallTrace.start(RequestOrigin.API, "Other", null, "", "");
        fixture.ledger.record(ResponseUsage.reported(10, 0, 10, null), quests, "openai", "", false);
        assertFalse(fixture.policy.tryReserve(charge(quests, 1)).allowed());
        assertTrue(fixture.policy.tryReserve(charge(shop, 80)).allowed());
        assertTrue(fixture.policy.tryReserve(charge(other, 80)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(shop, 80)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(shop, 1)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(quests, 1)).allowed());
    }

    @Test
    void moderationSkipsThePlayerCapAndSummarySpendsIt() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 0L, 10L, Map.of(), Map.of()));
        UUID player = UUID.randomUUID();
        CallTrace moderation = CallTrace.start(RequestOrigin.MODERATION, player, "", "");
        assertTrue(fixture.policy.tryReserve(charge(moderation, 10_000L)).allowed());
        CallTrace placeholder = CallTrace.start(RequestOrigin.PLACEHOLDER, player, "", "");
        QuotaPolicy.Decision playerHold = fixture.policy.tryReserve(charge(placeholder, 10));
        assertTrue(playerHold.allowed());
        assertFalse(fixture.policy.tryReserve(charge(placeholder, 1)).allowed());
        playerHold.hold().releaseWith(fixture.policy);
        CallTrace summary = CallTrace.start(RequestOrigin.SUMMARY, player, "", "");
        assertTrue(fixture.policy.tryReserve(charge(summary, 10)).allowed());
        assertFalse(fixture.policy.tryReserve(charge(summary, 1)).allowed());
    }

    @Test
    void disabledPolicyReservesNothing() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(QuotaSettings.off());
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, UUID.randomUUID(), "", "");
        fixture.ledger.record(ResponseUsage.reported(50, 50, 100, null), trace, "openai", "0|openai|m", false);
        assertTrue(fixture.policy.tryReserve(charge(trace, 1_000_000L)).allowed());
        assertTrue(fixture.policy.tryReserveRow("0|openai|m", 10L, 10L).allowed());
        assertEquals(0L, fixture.policy.used("0|openai|m"));
        assertTrue(fixture.logs.isEmpty());
    }

    @Test
    void rowReservationReleasesAndCountsSpentTokens() {
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8));
        fixture.policy.apply(new QuotaSettings(true, 0L, 0L, Map.of(), Map.of()));
        String row = "0|openai|m";
        QuotaPolicy.Decision hold = fixture.policy.tryReserveRow(row, 10L, 10L);
        assertTrue(hold.allowed());
        assertEquals(10L, fixture.policy.used(row));
        assertFalse(fixture.policy.tryReserveRow(row, 10L, 1L).allowed());
        hold.hold().releaseWith(fixture.policy);
        assertEquals(0L, fixture.policy.used(row));
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        fixture.ledger.record(ResponseUsage.reported(6, 4, 10, null), trace, "openai", row, false);
        assertFalse(fixture.policy.tryReserveRow(row, 10L, 1L).allowed());
    }

    @Test
    void quotaLogMasksAConfiguredName() {
        String canary = "sk-test-canary-value";
        Fixture fixture = fixture(LocalDate.of(2026, 10, 8), new AtomicLong(1_000L), List.of(canary));
        fixture.policy.apply(new QuotaSettings(true, 0L, 1L, Map.of(), Map.of()));
        UUID player = UUID.randomUUID();
        fixture.policy.noteName(player, canary);
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, player, "", "");
        fixture.ledger.record(ResponseUsage.reported(1, 0, 1, null), trace, "openai", "", false);
        assertFalse(fixture.policy.tryReserve(charge(trace, 5)).allowed());
        String logged = String.join("\n", fixture.logs);
        assertFalse(logged.contains(canary), logged);
        assertTrue(logged.contains("Token quota reached: player "), logged);
        assertFalse(SecretMask.redact("name " + canary, List.of(canary)).contains(canary));
    }

    private static QuotaPolicy.Decision spend(Fixture fixture, CallTrace trace, long tokens) {
        QuotaPolicy.Decision decision = fixture.policy.tryReserve(charge(trace, tokens));
        assertTrue(decision.allowed());
        int prompt = (int) Math.min(Integer.MAX_VALUE, tokens);
        fixture.ledger.record(ResponseUsage.reported(prompt, 0, prompt, null), trace, "openai", "", false);
        decision.hold().releaseWith(fixture.policy);
        return decision;
    }

    private static QuotaPolicy.Charge charge(CallTrace trace, long tokens) {
        return charge(trace, tokens, null);
    }

    private static QuotaPolicy.Charge charge(CallTrace trace, long tokens, QuotaPolicy.Membership membership) {
        return new QuotaPolicy.Charge(trace.origin(), trace.consumer(), trace.playerId(), null, tokens, membership);
    }

    private static Fixture fixture(LocalDate day) {
        return fixture(new AtomicReference<>(day), new AtomicLong(1_000L), List.of());
    }

    private static Fixture fixture(AtomicReference<LocalDate> today) {
        return fixture(today, new AtomicLong(1_000L), List.of());
    }

    private static Fixture fixture(LocalDate day, AtomicLong clock) {
        return fixture(new AtomicReference<>(day), clock, List.of());
    }

    private static Fixture fixture(LocalDate day, AtomicLong clock, List<String> secrets) {
        return fixture(new AtomicReference<>(day), clock, secrets);
    }

    private static Fixture fixture(AtomicReference<LocalDate> today, AtomicLong clock, List<String> secrets) {
        Logger logger = Logger.getLogger("quota-policy-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        List<String> logs = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.INFO.intValue() && record.getMessage() != null) {
                    logs.add(record.getMessage());
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
        QuotaPolicy policy = new QuotaPolicy(ledger, today::get, clock::get, logger, () -> secrets);
        return new Fixture(ledger, policy, logs);
    }

    private record Fixture(TokenLedger ledger, QuotaPolicy policy, List<String> logs) {
    }
}
