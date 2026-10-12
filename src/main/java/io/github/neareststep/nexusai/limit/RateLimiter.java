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
    private final int playerRequestsPerMinute;
    private final int playerRequestsPerDay;
    private final ConcurrentHashMap<UUID, WindowCounter> minuteWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WindowCounter> dayWindows = new ConcurrentHashMap<>();
    private final WindowCounter serverMinute;
    private final WindowCounter serverDay;

    /**
     * Server and per-player windows share the same limits.
     */
    public RateLimiter(int requestsPerMinute, int requestsPerDay) {
        this(requestsPerMinute, requestsPerDay, requestsPerMinute, requestsPerDay);
    }

    /**
     * @param requestsPerMinute server-wide minute cap, used by console and background tasks
     * @param playerRequestsPerMinute tighter cap applied only when a player id is present
     */
    public RateLimiter(int requestsPerMinute, int requestsPerDay, int playerRequestsPerMinute, int playerRequestsPerDay) {
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.requestsPerDay = Math.max(1, requestsPerDay);
        this.playerRequestsPerMinute = Math.max(1, playerRequestsPerMinute);
        this.playerRequestsPerDay = Math.max(1, playerRequestsPerDay);
        this.serverMinute = new WindowCounter(60_000L);
        this.serverDay = new WindowCounter(86_400_000L);
    }

    /**
     * @param playerId player UUID, or {@code null}/{@link #SERVER_SENTINEL} for global-only accounting
     * @return {@code true} if the request is allowed
     */
    public boolean tryAcquire(UUID playerId) {
        return tryAcquire(playerId, false);
    }

    /**
     * @param skipPlayerDay when true, the player day window is not taken. The player minute window
     *                      and both server windows still apply. A quota group uses this so its own
     *                      calendar-day request cap can replace {@code limits.player-requests-per-day}.
     */
    public boolean tryAcquire(UUID playerId, boolean skipPlayerDay) {
        UUID id = playerId == null ? SERVER_SENTINEL : playerId;
        long now = System.currentTimeMillis();

        if (!SERVER_SENTINEL.equals(id)) {
            WindowCounter minute = minuteWindows.computeIfAbsent(id, ignored -> new WindowCounter(60_000L));
            if (!minute.tryAcquire(now, playerRequestsPerMinute)) {
                return false;
            }
            if (!skipPlayerDay) {
                WindowCounter day = dayWindows.computeIfAbsent(id, ignored -> new WindowCounter(86_400_000L));
                if (!day.tryAcquire(now, playerRequestsPerDay)) {
                    return false;
                }
            }
        }

        return serverMinute.tryAcquire(now, requestsPerMinute) && serverDay.tryAcquire(now, requestsPerDay);
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
