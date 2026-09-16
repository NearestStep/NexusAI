package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Registers {@code %ainexus_generate_<prompt>%}.
 * Always returns immediately: cached text or configured fallback.
 */
public final class AiPlaceholderExpansion extends PlaceholderExpansion {

    private static final String GENERATE_PREFIX = "generate_";

    private final NexusAI plugin;
    private final PluginConfig config;
    private final AiCache cache;
    private final AiHttpClient httpClient;
    private final RateLimiter rateLimiter;

    public AiPlaceholderExpansion(
            NexusAI plugin,
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            RateLimiter rateLimiter
    ) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ainexus";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (!params.startsWith(GENERATE_PREFIX)) {
            return null;
        }

        String prompt = params.substring(GENERATE_PREFIX.length());
        if (!PromptValidation.isUsablePrompt(prompt, config.getMaxPromptLength())) {
            return config.getFallback();
        }

        String key = httpClient.cacheKey(prompt);
        return cache.get(key).orElseGet(() -> {
            UUID playerId = player != null ? player.getUniqueId() : RateLimiter.SERVER_SENTINEL;
            if (!rateLimiter.tryAcquire(playerId)) {
                return config.getFallback();
            }
            if (!config.hasApiKey()) {
                return config.getFallback();
            }

            httpClient.requestAsync(prompt).whenComplete((ignored, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.FINE, "Background AI generation failed", error);
                }
            });
            return config.getFallback();
        });
    }
}
