package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.context.RegionOwnership;
import io.github.neareststep.nexusai.i18n.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Delivers a moderation flag on the region that owns each staff member.
 * Chat events are already asynchronous, and Folia rejects {@code Bukkit.getScheduler()}.
 */
public final class FoliaStaffNotifier implements StaffNotifier {

    private final NexusAI plugin;

    public FoliaStaffNotifier(NexusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void flagged(String playerName, String message, String category, String reason) {
        MessageService messages = plugin.getMessageService();
        String text = messages.format("moderation.notify", Map.of(
                "player", safe(playerName, 32),
                "message", safe(message, 160),
                "category", safe(category, 40),
                "reason", safe(reason, 160)
        ));
        try {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduled -> deliver(text));
        } catch (Throwable thrown) {
            plugin.getLogger().warning("Failed to schedule a moderation notice: " + thrown.getMessage());
        }
    }

    private void deliver(String text) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null) {
                continue;
            }
            if (RegionOwnership.owned(online)) {
                notifyIfStaff(online, text);
                continue;
            }
            online.getScheduler().run(plugin, task -> notifyIfStaff(online, text), () ->
                    plugin.getLogger().fine("Skipped a moderation notice because the staff member left"));
        }
    }

    private static void notifyIfStaff(Player online, String text) {
        if (online == null || !online.isOnline() || !online.hasPermission("nexusai.moderation.notify")) {
            return;
        }
        online.sendMessage(text);
    }

    static String safe(String value, int maxCodePoints) {
        String clean = PlayerInput.sanitize(value == null ? "" : value)
                .replace('{', '(')
                .replace('}', ')')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (clean.codePointCount(0, clean.length()) <= maxCodePoints) {
            return clean;
        }
        return clean.substring(0, clean.offsetByCodePoints(0, maxCodePoints)) + "...";
    }
}
