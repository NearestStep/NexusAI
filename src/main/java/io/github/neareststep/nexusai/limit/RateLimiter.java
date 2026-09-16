package io.github.neareststep.nexusai.limit;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-player and server-wide request windows (minute + day).
 */
public final class RateLimiter {

    public static final UUID SERVER_SENTINEL = new UUID(0L, 0L);

    private final int requestsPerMinute;
    private final int requestsPerDay;
    private final ConcurrentHashMap<UUID, WindowCounter> minuteWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WindowCounter> dayWindows = new ConcurrentHashMap<>();
    private final WindowCounter serverMinute;
    private final WindowCounter serverDay;

    public RateLimiter(int requestsPerMinute, int requestsPerDay) {
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.requestsPerDay = Math.max(1, requestsPerDay);
        this.serverMinute = new WindowCounter(60_000L);
        this.serverDay = new WindowCounter(86_400_000L);
    }

    /**
     * @param playerId player UUID, or {@code null}/{@link #SERVER_SENTINEL} for global-only accounting
     * @return {@code true} if the request is allowed
     */
    public boolean tryAcquire(UUID playerId) {
        UUID id = playerId == null ? SERVER_SENTINEL : playerId;
        long now = System.currentTimeMillis();

        if (!serverMinute.tryAcquire(now, requestsPerMinute) || !serverDay.tryAcquire(now, requestsPerDay)) {
            return false;
        }

        if (SERVER_SENTINEL.equals(id)) {
            return true;
        }

        WindowCounter minute = minuteWindows.computeIfAbsent(id, ignored -> new WindowCounter(60_000L));
        WindowCounter day = dayWindows.computeIfAbsent(id, ignored -> new WindowCounter(86_400_000L));
        if (!minute.tryAcquire(now, requestsPerMinute) || !day.tryAcquire(now, requestsPerDay)) {
            // Best-effort: server counters already incremented; acceptable for soft limits.
            return false;
        }
        return true;
    }

    private static final class WindowCounter {
        private final long windowMillis;
        private final Object lock = new Object();
        private long windowStart;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long windowMillis) {
            this.windowMillis = windowMillis;
            this.windowStart = System.currentTimeMillis();
        }

        private boolean tryAcquire(long now, int limit) {
            synchronized (lock) {
                if (now - windowStart >= windowMillis) {
                    windowStart = now;
                    count.set(0);
                }
                if (count.get() >= limit) {
                    return false;
                }
                count.incrementAndGet();
                return true;
            }
        }
    }
}
