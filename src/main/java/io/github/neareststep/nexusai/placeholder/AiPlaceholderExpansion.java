package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Registers {@code %ainexus_generate_<prompt>%} (unique pool) and
 * {@code %ainexus_cached_<prompt>%} (shared TTL cache).
 */
public final class AiPlaceholderExpansion extends PlaceholderExpansion {

    private static final String GENERATE_PREFIX = "generate_";
    private static final String CACHED_PREFIX = "cached_";

    private final NexusAI plugin;
    private final PluginConfig config;
    private final AiCache cache;
    private final AiHttpClient httpClient;
    private final RateLimiter rateLimiter;
    private final AiPool pool;
    private final PoolService poolService;

    public AiPlaceholderExpansion(
            NexusAI plugin,
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            RateLimiter rateLimiter,
            AiPool pool,
            PoolService poolService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.httpClient = httpClient;
        this.rateLimiter = rateLimiter;
        this.pool = pool;
        this.poolService = poolService;
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
        if (params.startsWith(GENERATE_PREFIX)) {
            return resolveGenerate(player, params.substring(GENERATE_PREFIX.length()));
        }
        if (params.startsWith(CACHED_PREFIX)) {
            return resolveCached(player, params.substring(CACHED_PREFIX.length()));
        }
        return null;
    }

    private String resolveGenerate(Player player, String prompt) {
        if (!PromptValidation.isUsablePrompt(prompt, config.getMaxPromptLength())) {
            return config.getFallback();
        }
        Optional<String> answer = pool.poll(prompt);
        poolService.onConsume(prompt);
        if (answer.isEmpty()) {
            return config.getFallback();
        }
        Optional<PoolEntry> entry = poolService.findEntry(prompt);
        if (entry.isPresent() && entry.get().hasVars()) {
            return VarSubstitutor.apply(answer.get(), entry.get().vars(), player);
        }
        return answer.get();
    }

    private String resolveCached(Player player, String prompt) {
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
