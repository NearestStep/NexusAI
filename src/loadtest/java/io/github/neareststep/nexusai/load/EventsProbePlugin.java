package io.github.neareststep.nexusai.load;

import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.event.NexusActionEvent;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusModerationFlagEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Logs generation events so a smoke run can check that pre comes before post.
 * Built as its own jar. It is not part of the release plugin.
 */
public final class EventsProbePlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("nexusai-events-probe enabled.");
    }

    @EventHandler
    public void onPre(NexusPreGenerateEvent event) {
        line("pre", event.requestId(), event.origin(), event.consumer());
    }

    @EventHandler
    public void onPost(NexusPostGenerateEvent event) {
        line("post", event.requestId(), event.origin(), event.consumer());
    }

    @EventHandler
    public void onFail(NexusGenerateFailEvent event) {
        line("fail", event.requestId(), event.origin(), event.consumer());
    }

    @EventHandler
    public void onProviderError(NexusProviderErrorEvent event) {
        line("provider-error", event.requestId(), event.origin(), event.consumer());
    }

    @EventHandler
    public void onAction(NexusActionEvent event) {
        StringBuilder line = new StringBuilder();
        line.append("NEXUSAI_EVENT phase=action requestId=").append(event.requestId());
        line.append(" character=").append(event.characterId());
        line.append(" primary=").append(Bukkit.isPrimaryThread());
        Player player = event.player();
        if (player != null) {
            try {
                line.append(" ownedByRegion=").append(Bukkit.isOwnedByCurrentRegion(player));
            } catch (Throwable ignored) {
                // The player region check is not on every server. Paper still logs primary=.
            }
        }
        getLogger().info(line.toString());
    }

    @EventHandler
    public void onFlag(NexusModerationFlagEvent event) {
        getLogger().info("NEXUSAI_EVENT phase=moderation player=" + event.playerId());
    }

    private void line(String phase, long requestId, RequestOrigin origin, String consumer) {
        getLogger().info("NEXUSAI_EVENT phase=" + phase
                + " requestId=" + requestId
                + " origin=" + origin
                + " consumer=" + consumer);
    }
}
