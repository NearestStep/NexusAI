package io.github.neareststep.nexusai.generate;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Reads player state on the thread that owns the entity.
 * {@link #run} schedules {@code body} with {@code plugin}, which is NexusAI, so disabling the
 * caller plugin does not cancel the task. {@code retired} runs when the entity is gone.
 */
public interface RegionTasks {

    /** True when the calling thread already owns {@code player}. */
    boolean owns(Player player);

    void run(Plugin plugin, Player player, Runnable body, Runnable retired);
}
