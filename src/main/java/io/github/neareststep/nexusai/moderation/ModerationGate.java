package io.github.neareststep.nexusai.moderation;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide checks per minute and a per-player cooldown.
 * A skipped message does not consume either window.
 */
public final class ModerationGate {

    private final int maxPerMinute;
    private final long cooldownMillis;
    private final Object lock = new Object();
    private long windowStart;
    private int windowCount;
    private final ConcurrentHashMap<UUID, Long> lastCheck = new ConcurrentHashMap<>();

    public ModerationGate(int maxPerMinute, int cooldownSeconds) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
    }

    /**
     * @return {@code null} when the check may proceed and both windows have been reserved
     */
    public ModerationService.Skip tryReserve(UUID playerId, long now) {
        synchronized (lock) {
            if (playerId != null && cooldownMillis > 0) {
                Long previous = lastCheck.get(playerId);
                if (previous != null && now - previous < cooldownMillis) {
                    return ModerationService.Skip.COOLDOWN;
                }
            }
            if (windowStart == 0 || now - windowStart >= 60_000L) {
                windowStart = now;
                windowCount = 0;
            }
            if (windowCount >= maxPerMinute) {
                return ModerationService.Skip.MINUTE_CAP;
            }
            windowCount++;
            if (playerId != null) {
                lastCheck.put(playerId, now);
            }
            return null;
        }
    }

    /**
     * Gives back a reservation when the daily cap rejects the check before any HTTP call.
     */
    public void release(UUID playerId, long now) {
        synchronized (lock) {
            if (now - windowStart < 60_000L && windowCount > 0) {
                windowCount--;
            }
            if (playerId != null) {
                Long stamp = lastCheck.get(playerId);
                if (stamp != null && stamp == now) {
                    lastCheck.remove(playerId);
                }
            }
        }
    }
}
