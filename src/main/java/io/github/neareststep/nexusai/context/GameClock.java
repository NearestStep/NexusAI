package io.github.neareststep.nexusai.context;

/**
 * In-game time of day, coarse enough to share a cache entry.
 * Tick {@code 0} is 06:00. A Minecraft day is 24_000 ticks, and one tick is 50 ms,
 * so a clock with minutes changes about every 0.8 s and never hits {@code cached_}.
 * The value is one of four periods and stays the same for 6_000 ticks (five real minutes):
 * morning (06:00–12:00), day (12:00–18:00), evening (18:00–00:00), night (00:00–06:00).
 */
public final class GameClock {

    private GameClock() {
    }

    public static String format(long worldTicks) {
        long ticks = Math.floorMod(worldTicks, 24_000L);
        if (ticks < 6_000L) {
            return "morning";
        }
        if (ticks < 12_000L) {
            return "day";
        }
        if (ticks < 18_000L) {
            return "evening";
        }
        return "night";
    }

    public static String weather(boolean storm, boolean thunder) {
        if (thunder) {
            return "thunder";
        }
        if (storm) {
            return "rain";
        }
        return "clear";
    }
}
