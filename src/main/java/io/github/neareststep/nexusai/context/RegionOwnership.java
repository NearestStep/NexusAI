package io.github.neareststep.nexusai.context;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Whether the current thread owns a player's region.
 * A missing server does not. {@link #install(Probe)} replaces the check; {@link #reset()} restores it.
 */
public final class RegionOwnership {

    @FunctionalInterface
    public interface Probe {
        boolean owned(Player player);
    }

    private static volatile Probe probe;

    private RegionOwnership() {
    }

    public static boolean installed() {
        return probe != null;
    }

    public static void install(Probe next) {
        probe = next;
    }

    public static void reset() {
        probe = null;
    }

    /**
     * {@code false} when {@code player} is null, the server is absent, or the check fails.
     * Callers that cannot hop treat that as "do not read region state".
     */
    public static boolean owned(Player player) {
        if (player == null) {
            return false;
        }
        Probe current = probe;
        try {
            if (current != null) {
                return current.owned(player);
            }
            if (Bukkit.getServer() == null) {
                return false;
            }
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
