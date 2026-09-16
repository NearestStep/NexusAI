package io.github.neareststep.nexusai.limit;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void rejectsAfterMinuteLimit() {
        RateLimiter limiter = new RateLimiter(2, 1000);
        UUID player = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(player));
        assertTrue(limiter.tryAcquire(player));
        assertFalse(limiter.tryAcquire(player));
    }

    @Test
    void differentPlayersShareServerBudget() {
        // Same rpm applies per-player and server-wide (plan: shared server counters).
        RateLimiter limiter = new RateLimiter(2, 1000);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertTrue(limiter.tryAcquire(a));
        assertTrue(limiter.tryAcquire(b));
        assertFalse(limiter.tryAcquire(a));
        assertFalse(limiter.tryAcquire(b));
    }

    @Test
    void nullPlayerUsesServerSentinel() {
        RateLimiter limiter = new RateLimiter(1, 1000);
        assertTrue(limiter.tryAcquire(null));
        assertFalse(limiter.tryAcquire(null));
    }
}
