package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.StopGate;
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

    /**
     * Delivers the result of an AI request. A request cancelled by plugin stop is skipped
     * without a count: it was already counted once as cancelled.
     */
    public static void runReply(JavaPlugin plugin, CommandSender sender, Throwable error, Runnable task, Logger logger) {
        if (StopGate.isStopFailure(error)) {
            return;
        }
        run(plugin, sender, task, logger);
    }

    public static void run(JavaPlugin plugin, CommandSender sender, Runnable task, Logger logger) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Throwable thrown) {
                logger.log(Level.WARNING, "NexusAI command result failed", thrown);
            }
        };
        if (StopGate.drop(plugin)) {
            // Stopping: nothing can be scheduled. Disable logs the count once.
            return;
        }
        try {
            if (sender instanceof Entity entity) {
                entity.getScheduler().run(plugin, scheduled -> safe.run(), () ->
                        logger.fine("Skipped a command result because the target is no longer valid"));
                return;
            }
            plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> safe.run());
        } catch (Throwable thrown) {
            if (StopGate.dropFailed(plugin)) {
                return;
            }
            logger.log(Level.WARNING, "Failed to schedule a command result", thrown);
        }
    }
}
