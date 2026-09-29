package io.github.neareststep.nexusai.context;

/**
 * In-game time of day. Tick {@code 0} is 06:00, and the day/night split is noon-to-dusk versus dusk-to-dawn.
 */
public final class GameClock {

    private GameClock() {
    }

    public static String format(long worldTicks) {
        long ticks = Math.floorMod(worldTicks, 24_000L);
        String phase = ticks < 12_000L ? "day" : "night";
        int hours = (int) ((ticks / 1_000L) + 6L) % 24;
        int minutes = (int) ((ticks % 1_000L) * 60L / 1_000L);
        return phase + " " + String.format("%02d:%02d", hours, minutes);
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
