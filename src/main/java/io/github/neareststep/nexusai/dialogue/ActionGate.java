package io.github.neareststep.nexusai.dialogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player cooldown, daily cap, and permission check for one character action.
 * A refusal does not start the cooldown or spend the daily cap.
 */
public final class ActionGate {

    private final ConcurrentHashMap<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DayCount> daily = new ConcurrentHashMap<>();

    public Decision admit(CharacterAction action, String characterId, UUID player, boolean permitted, long nowMillis, ZoneId zone) {
        if (action == null || player == null) {
            return Decision.deny("unknown action");
        }
        if (action.permission() != null && !permitted) {
            return Decision.deny("permission");
        }
        String key = key(player, characterId, action.name());
        long until = cooldownUntil.getOrDefault(key, 0L);
        if (action.cooldownSeconds() > 0 && nowMillis < until) {
            return Decision.deny("cooldown");
        }
        if (action.dailyLimit() > 0) {
            long day = day(nowMillis, zone);
            DayCount count = daily.get(key);
            if (count != null && count.day == day && count.count >= action.dailyLimit()) {
                return Decision.deny("daily limit");
            }
        }
        return Decision.allow();
    }

    public void record(CharacterAction action, String characterId, UUID player, long nowMillis, ZoneId zone) {
        if (action == null || player == null) {
            return;
        }
        String key = key(player, characterId, action.name());
        if (action.cooldownSeconds() > 0) {
            cooldownUntil.put(key, nowMillis + action.cooldownSeconds() * 1000L);
        }
        if (action.dailyLimit() > 0) {
            long day = day(nowMillis, zone);
            daily.compute(key, (ignored, current) -> {
                if (current == null || current.day != day) {
                    return new DayCount(day, 1);
                }
                return new DayCount(day, current.count + 1);
            });
        }
    }

    static long day(long nowMillis, ZoneId zone) {
        ZoneId effective = zone == null ? ZoneId.systemDefault() : zone;
        return LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), effective).toEpochDay();
    }

    private static String key(UUID player, String characterId, String action) {
        return player + "\u0000" + (characterId == null ? "" : characterId) + "\u0000" + action;
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "ran");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
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
