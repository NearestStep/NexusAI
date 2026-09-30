package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitHeadersTest {

    @Test
    void bareRemainingHeaderCountsAsRequestsAndAMissingHeaderIsNotZero() {
        RateLimitHeaders.Snapshot bare = RateLimitHeaders.parse(
                Map.of("x-ratelimit-remaining", List.of("0")), 1_000L);
        assertEquals(0L, bare.remainingRequests());
        assertTrue(bare.exhausted(0));

        RateLimitHeaders.Snapshot specific = RateLimitHeaders.parse(
                Map.of(
                        "x-ratelimit-remaining-requests", List.of("3"),
                        "x-ratelimit-remaining", List.of("0")),
                1_000L);
        assertEquals(3L, specific.remainingRequests());
        assertFalse(specific.exhausted(0));

        RateLimitHeaders.Snapshot absent = RateLimitHeaders.parse(Map.of("content-type", List.of("application/json")), 1_000L);
        assertNull(absent.remainingRequests());
        assertNull(absent.remainingTokens());
        assertFalse(absent.exhausted(0));
    }

    @Test
    void resetHeaderFormats() {
        assertEquals(2_500L, RateLimitHeaders.parseReset("1.5", 1_000L));
        assertEquals(2_000L, RateLimitHeaders.parseReset("1s", 1_000L));
        assertEquals(1_700_000_000_000L, RateLimitHeaders.parseReset("1700000000000", 1_000L));
        assertEquals(1_700_000_000_000L, RateLimitHeaders.parseReset("1700000000", 1_000L));
        assertEquals(11_000L, RateLimitHeaders.parse(
                Map.of("retry-after", List.of("10")), 1_000L).resetAtMillis());
    }
}
