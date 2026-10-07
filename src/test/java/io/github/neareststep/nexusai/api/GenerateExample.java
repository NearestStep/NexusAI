package io.github.neareststep.nexusai.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Appendix A. Compiles against the public API. Not executed.
 */
final class GenerateExample {

    void sample(Plugin plugin, Player player, String questName) {
        NexusAIApi.generate(plugin, GenerationRequest.prompt("quests:intro")
                        .player(player)
                        .var("quest", questName)
                        .label("quest-intro")
                        .build())
                .thenAccept(result -> player.getScheduler().run(plugin, task -> {
                    player.sendMessage(result.text());
                    if (!result.success()) {
                        plugin.getLogger().fine("AI failed: " + result.error().map(GenerationError::kind).orElse(null));
                    }
                }, null));
    }
}
