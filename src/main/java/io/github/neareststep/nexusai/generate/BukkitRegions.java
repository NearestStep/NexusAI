package io.github.neareststep.nexusai.generate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Paper and Folia region ownership. A missing server does not own the player. */
public final class BukkitRegions implements RegionTasks {

    @Override
    public boolean owns(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (Bukkit.getServer() == null) {
                return false;
            }
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void run(Plugin plugin, Player player, Runnable body, Runnable retired) {
        if (plugin == null || player == null) {
            if (retired != null) {
                retired.run();
            }
            return;
        }
        player.getScheduler().run(plugin, task -> body.run(), retired);
    }
}
