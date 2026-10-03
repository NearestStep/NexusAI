package io.github.neareststep.nexusai.load;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Loaded only after {@link TickEventProbe} has confirmed the Paper event exists.
 * Keeping the reference off {@link LoadDriverPlugin} lets that class fail with a clear error
 * on a server that does not have the event.
 */
final class TickMonitor implements Listener {

    private final LoadDriverPlugin plugin;

    TickMonitor(LoadDriverPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTick(ServerTickEndEvent event) {
        plugin.onTick(event.getTickDuration());
    }
}
