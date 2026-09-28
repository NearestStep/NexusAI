package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.limit.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestGateTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private RequestGate gate(int perMinute, long backoffInitial, long backoffMax, long ratePause, long authPause) {
        return new RequestGate(
                new RateLimiter(perMinute, 10_000),
                backoffInitial,
                backoffMax,
                ratePause,
                authPause,
                clock::get
        );
    }

    @Test
    void rateLimitRejectsWithoutCallingFurtherSlots() {
        RequestGate gate = gate(1, 1_000, 8_000, 60_000, 300_000);
        assertTrue(gate.tryAdmit(null, "a", false).isEmpty());
        assertTrue(gate.tryAdmit(null, "b", false).isPresent());
    }

    @Test
    void providerErrorBacksOffOnlyThatPrompt() {
        RequestGate gate = gate(100, 1_000, 8_000, 60_000, 300_000);
        gate.recordFailure("tip", AiErrorKind.OTHER);
        assertTrue(gate.isBlocked("tip"));
        assertFalse(gate.isBlocked("other"));
        assertFalse(gate.isPaused());

        clock.addAndGet(1_000);
        assertFalse(gate.isBlocked("tip"));
    }

    @Test
    void backoffGrowsUntilMax() {
        RequestGate gate = gate(100, 1_000, 3_000, 60_000, 300_000);
        gate.recordFailure("tip", AiErrorKind.TIMEOUT);
        clock.addAndGet(1_000);
        assertFalse(gate.isBlocked("tip"));

        gate.recordFailure("tip", AiErrorKind.TIMEOUT);
        clock.addAndGet(1_999);
        assertTrue(gate.isBlocked("tip"));
        clock.addAndGet(1);
        assertFalse(gate.isBlocked("tip"));

        gate.recordFailure("tip", AiErrorKind.TIMEOUT);
        clock.addAndGet(3_000);
        assertFalse(gate.isBlocked("tip"));
    }

    @Test
    void status429PausesEveryPrompt() {
        RequestGate gate = gate(100, 1_000, 8_000, 60_000, 300_000);
        gate.recordFailure("tip", AiErrorKind.RATE_LIMIT);
        assertTrue(gate.isPaused());
        assertEquals(AiErrorKind.RATE_LIMIT, gate.pauseKind());
        assertTrue(gate.isBlocked("other"));
        assertTrue(gate.tryAdmit(null, "other", false).isPresent());

        clock.addAndGet(60_000);
        assertFalse(gate.isPaused());
        assertTrue(gate.tryAdmit(null, "other", false).isEmpty());
    }

    @Test
    void authAndQuotaPauseLongerThanRateLimit() {
        RequestGate quota = gate(100, 0, 0, 60_000, 300_000);
        quota.recordFailure("tip", AiErrorKind.QUOTA);
        clock.addAndGet(60_000);
        assertTrue(quota.isPaused());
        clock.addAndGet(240_000);
        assertFalse(quota.isPaused());

        clock.set(1_000_000L);
        RequestGate auth = gate(100, 0, 0, 60_000, 300_000);
        auth.recordFailure("tip", AiErrorKind.BAD_KEY);
        assertEquals(AiErrorKind.BAD_KEY, auth.pauseKind());
        clock.addAndGet(299_000);
        assertTrue(auth.isPaused());
    }

    @Test
    void successClearsPauseOnlyIfNothingFailedDuringTheCall() {
        RequestGate gate = gate(100, 5_000, 30_000, 60_000, 300_000);
        long pauseStamp = gate.pauseStamp();
        long epoch = gate.failureEpoch("tip");
        gate.recordFailure("tip", AiErrorKind.RATE_LIMIT);
        gate.recordSuccess("tip", pauseStamp, epoch);
        assertTrue(gate.isPaused());
        assertTrue(gate.isBlocked("tip"));

        long laterStamp = gate.pauseStamp();
        long laterEpoch = gate.failureEpoch("tip");
        gate.recordSuccess("tip", laterStamp, laterEpoch);
        assertFalse(gate.isPaused());
        assertFalse(gate.isBlocked("tip"));
    }

    @Test
    void adminProbeSuccessDoesNotClearPause() {
        RequestGate gate = gate(100, 5_000, 30_000, 6_000, 8_000);
        gate.recordFailure("tip", AiErrorKind.BAD_KEY);
        long pauseStamp = gate.pauseStamp();
        long epoch = gate.failureEpoch("probe");
        gate.recordSuccess("probe", pauseStamp, epoch, false);
        assertTrue(gate.isPaused());
        clock.addAndGet(6_000);
        assertTrue(gate.isPaused());
        clock.addAndGet(2_000);
        assertFalse(gate.isPaused());
    }

    @Test
    void adminProbeFailureDoesNotExtendPause() {
        RequestGate gate = gate(100, 1_000, 8_000, 6_000, 12_000);
        gate.recordFailure("tip", AiErrorKind.BAD_KEY);
        clock.addAndGet(5_000L);
        assertEquals(7_000L, gate.pauseRemainingMillis());
        gate.recordFailure("probe", AiErrorKind.BAD_KEY, 0L, false);
        gate.recordFailure("probe", AiErrorKind.RATE_LIMIT, 30L, false);
        gate.recordFailure("probe", AiErrorKind.QUOTA, 0L, false);
        assertEquals(7_000L, gate.pauseRemainingMillis());
        assertEquals(AiErrorKind.BAD_KEY, gate.pauseKind());
        gate.recordFailure("live", AiErrorKind.BAD_KEY);
        assertEquals(12_000L, gate.pauseRemainingMillis());
    }

    @Test
    void retryAfterCannotShortenTheConfiguredPause() {
        RequestGate gate = gate(100, 1_000, 8_000, 6_000, 8_000);
        gate.recordFailure("tip", AiErrorKind.RATE_LIMIT, 1L);
        clock.addAndGet(1_300);
        assertTrue(gate.isPaused());
        gate.recordFailure("other", AiErrorKind.RATE_LIMIT, 30L);
        clock.addAndGet(6_000);
        assertTrue(gate.isPaused());
        clock.addAndGet(24_000);
        assertFalse(gate.isPaused());
    }

    @Test
    void bypassSkipsPauseButNotTheRateLimit() {
        RequestGate gate = gate(1, 1_000, 8_000, 60_000, 300_000);
        gate.recordFailure("tip", AiErrorKind.BAD_KEY);
        assertTrue(gate.isPaused());
        assertTrue(gate.tryAdmit(UUID.randomUUID(), "probe", true).isEmpty());
        assertTrue(gate.tryAdmit(null, "probe-2", true).isPresent());
    }
}
