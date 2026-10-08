package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.context.RegionOwnership;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Copies a public chat line and returns immediately.
 * The model check runs later on the HTTP executor, so delivery is not delayed.
 * Cancelled chat is ignored: that line was not sent to the server.
 */
public final class ChatModerationListener implements Listener {

    /**
     * One moderation submit. Production forwards to {@link ModerationService#submit}.
     */
    interface Gate {
        boolean enabled();

        void submit(UUID playerId, String name, String message, boolean bypass);
    }

    private final Plugin plugin;
    private final Gate gate;
    private final Logger logger;

    public ChatModerationListener(NexusAI plugin) {
        this(plugin, new Gate() {
            @Override
            public boolean enabled() {
                ModerationService service = plugin.getModerationService();
                return service != null && service.enabled();
            }

            @Override
            public void submit(UUID playerId, String name, String message, boolean bypass) {
                ModerationService service = plugin.getModerationService();
                if (service != null) {
                    service.submit(playerId, name, message, bypass);
                }
            }
        }, plugin.getLogger());
    }

    ChatModerationListener(Plugin plugin, Gate gate, Logger logger) {
        this.plugin = plugin;
        this.gate = gate;
        this.logger = logger == null ? Logger.getLogger("nexusai") : logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (gate == null || !gate.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        String text = plain(event.message());
        if (RegionOwnership.owned(player)) {
            submit(player, playerId, text);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            submit(player, playerId, text);
        }, () -> logger.fine("Skipped chat moderation because the player left"));
    }

    private void submit(Player player, UUID playerId, String text) {
        String name = player.getName();
        gate.submit(playerId, name == null ? "" : name, text, player.hasPermission("nexusai.moderation.bypass"));
    }

    static String plain(Component component) {
        if (component == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
