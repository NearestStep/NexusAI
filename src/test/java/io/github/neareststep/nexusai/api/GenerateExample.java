package io.github.neareststep.nexusai.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;

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

    void register(Plugin plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("NexusAI") && NexusAIApi.API_VERSION >= 3) {
            NexusAIApi.registerPrompt(plugin, "intro", PromptDefinition.builder(
                            "Write a two-sentence intro for the quest {quest}.")
                    .format("short")
                    .maxTokens(120)
                    .ttl(Duration.ofMinutes(30))
                    .fallback("A new quest awaits.")
                    .build());
        }
    }
}
