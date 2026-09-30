package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.NexusAI;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * While a session is open, the player's chat is cancelled and sent to the character.
 * Cancellation is at {@link EventPriority#HIGHEST} so earlier listeners, including chat moderation, still see the line.
 */
public final class DialogueListener implements Listener {

    private final NexusAI plugin;

    public DialogueListener(NexusAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAdventureChat(AsyncChatEvent event) {
        capture(event.getPlayer(), PlainTextComponentSerializer.plainText().serialize(event.message()), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        capture(event.getPlayer(), event.getMessage(), event);
    }

    private void capture(Player player, String text, org.bukkit.event.Cancellable event) {
        DialogueService service = plugin.getDialogueService();
        if (service == null || player == null || !service.capturesChat(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        if (!service.consumeChat(player.getUniqueId(), text)) {
            return;
        }
        service.acceptChat(player, text);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DialogueService service = plugin.getDialogueService();
        if (service != null) {
            service.quit(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getWorld() == event.getTo().getWorld()) {
            return;
        }
        DialogueService service = plugin.getDialogueService();
        if (service != null) {
            service.onMove(event.getPlayer(), event.getTo());
        }
    }
}
