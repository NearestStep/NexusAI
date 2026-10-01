package io.github.neareststep.nexusai.dialogue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dialogue limits that are separate from placeholder rate limits:
 * message cooldown, conversations per player per local day, and message length.
 */
public final class DialogueBudget {

    private final ConcurrentHashMap<UUID, Long> lastMessageAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, DayCount> conversations = new ConcurrentHashMap<>();

    public boolean cooldownReady(UUID player, long nowMillis, int cooldownMillis) {
        if (player == null || cooldownMillis <= 0) {
            return true;
        }
        Long last = lastMessageAt.get(player);
        return last == null || nowMillis - last >= cooldownMillis;
    }

    public void markMessage(UUID player, long nowMillis) {
        if (player != null) {
            lastMessageAt.put(player, nowMillis);
        }
    }

    /**
     * @param limit {@code 0} means no daily cap
     */
    public boolean tryStartConversation(UUID player, long nowMillis, int limit, java.time.ZoneId zone) {
        if (player == null || limit <= 0) {
            return true;
        }
        long day = ActionGate.day(nowMillis, zone);
        DayCount[] holder = new DayCount[1];
        conversations.compute(player, (ignored, current) -> {
            DayCount base = current == null || current.day != day ? new DayCount(day, 0) : current;
            holder[0] = base;
            if (base.count >= limit) {
                return base;
            }
            return new DayCount(day, base.count + 1);
        });
        DayCount seen = holder[0];
        return seen != null && seen.count < limit;
    }

    public static String clampMessage(String sanitized, int maxLength) {
        if (sanitized == null) {
            return "";
        }
        return sanitized;
    }

    public static boolean tooLong(String sanitized, int maxLength) {
        return sanitized != null && maxLength > 0 && sanitized.length() > maxLength;
    }

    private static final class DayCount {
        private final long day;
        private final int count;

        private DayCount(long day, int count) {
            this.day = day;
            this.count = count;
        }
    }
}
