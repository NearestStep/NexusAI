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

/**
 * Copies a public chat line and returns immediately.
 * The model check runs later on the HTTP executor, so delivery is not delayed.
 * Cancelled chat is ignored: that line was not sent to the server.
 */
public final class ChatModerationListener implements Listener {

    private final NexusAI plugin;

    public ChatModerationListener(NexusAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ModerationService service = plugin.getModerationService();
        if (service == null || !service.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        java.util.UUID playerId = player.getUniqueId();
        String text = plain(event.message());
        if (RegionOwnership.owned(player)) {
            submit(service, player, playerId, text);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline()) {
                return;
            }
            submit(service, player, playerId, text);
        }, () -> plugin.getLogger().fine("Skipped chat moderation because the player left"));
    }

    private static void submit(ModerationService service, Player player, java.util.UUID playerId, String text) {
        String name = player.getName();
        service.submit(playerId, name == null ? "" : name, text, player.hasPermission("nexusai.moderation.bypass"));
    }

    static String plain(Component component) {
        if (component == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
