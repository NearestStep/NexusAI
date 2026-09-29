package io.github.neareststep.nexusai.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Delivers a command result on the region that owns the sender.
 * Paper, Purpur, and Folia share these schedulers. Folia rejects {@code Bukkit.getScheduler()}.
 */
public final class SenderTasks {

    private SenderTasks() {
    }

    public static void run(JavaPlugin plugin, CommandSender sender, Runnable task, Logger logger) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable thrown) {
                logger.log(Level.WARNING, "NexusAI command result failed", thrown);
            }
        };
        try {
            if (sender instanceof Entity entity) {
                entity.getScheduler().run(plugin, scheduled -> safe.run(), () ->
                        logger.fine("Skipped a command result because the target is no longer valid"));
                return;
            }
            plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> safe.run());
        } catch (Throwable thrown) {
            logger.log(Level.WARNING, "Failed to schedule a command result", thrown);
        }
    }
}
