package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.context.RegionOwnership;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Paper and Folia region ownership. A missing server does not own the player. */
public final class BukkitRegions implements RegionTasks {

    @Override
    public boolean owns(Player player) {
        return RegionOwnership.owned(player);
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
