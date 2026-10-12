package io.github.neareststep.nexusai.load;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.api.NexusContextProvider;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.event.NexusActionEvent;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusModerationFlagEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import io.github.neareststep.nexusai.prompt.PromptContext;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Logs generation events so a smoke run can check that pre comes before post.
 * Built as its own jar. It is not part of the release plugin.
 */
public final class EventsProbePlugin extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("nexusai-events-probe enabled.");
        registerSmokeContext();
    }

    /**
     * Context id {@code smoke}. Console {@code /nai test} does not collect context, so this
     * plugin calls {@code ContextService.collect} itself. {@code provide} logs {@code NEXUSAI_CONTEXT}.
     */
    private void registerSmokeContext() {
        NexusAIApi.registerContextProvider(this, new SmokeContext(getLogger()));
        if (!NexusAIApi.contextProviderIds().contains("smoke")) {
            getLogger().severe("context provider smoke was not accepted");
            return;
        }
        Plugin installed = Bukkit.getPluginManager().getPlugin("NexusAI");
        if (!(installed instanceof NexusAI nexus) || nexus.getContextService() == null) {
            getLogger().severe("NexusAI context service is not available");
            return;
        }
        ContextRequest request = new ContextRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Smoke",
                "",
                "context_ping",
                ContextRequest.Purpose.PLACEHOLDER);
        nexus.getContextService()
                .collect(request, PromptContext.of(List.of("smoke")))
                .whenComplete((block, error) -> {
                    if (error != null || block == null || !block.contains("smoke-ok")) {
                        getLogger().severe("context provider smoke did not contribute");
                    }
                });
    }

    static final class SmokeContext implements NexusContextProvider {
        private final Logger logger;

        SmokeContext(Logger logger) {
            this.logger = logger;
        }

        @Override
        public String id() {
            return "smoke";
        }

        @Override
        public CompletableFuture<String> provide(ContextRequest request) {
            String world = request == null || request.world() == null ? "" : request.world();
            logger.info("NEXUSAI_CONTEXT id=smoke world=" + world);
            return CompletableFuture.completedFuture("smoke-ok");
        }
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
