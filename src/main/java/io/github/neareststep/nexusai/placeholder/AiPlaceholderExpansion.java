package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
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
    private PluginConfig config;
    private AiCache cache;
    private AiHttpClient httpClient;
    private AiPool pool;
    private PoolService poolService;
    private PromptCatalog prompts;

    public AiPlaceholderExpansion(
            NexusAI plugin,
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            AiPool pool,
            PoolService poolService,
            PromptCatalog prompts
    ) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
        this.httpClient = httpClient;
        this.pool = pool;
        this.poolService = poolService;
        this.prompts = prompts == null ? PromptCatalog.empty() : prompts;
    }

    /**
     * Points an already registered expansion at the services created by {@code /nai reload}
     * without asking PlaceholderAPI to register it again.
     */
    public void bind(
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            AiPool pool,
            PoolService poolService,
            PromptCatalog prompts
    ) {
        this.config = config;
        this.cache = cache;
        this.httpClient = httpClient;
        this.pool = pool;
        this.poolService = poolService;
        this.prompts = prompts == null ? PromptCatalog.empty() : prompts;
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

    private String resolveGenerate(Player player, String raw) {
        plugin.getUnpooledGenerateLog().note(
                raw, config.isPoolEnabled() && poolService.findEntry(raw).isPresent());
        ResolvedPrompt resolved = resolve(player, raw);
        if (!resolved.usable()) {
            return resolved.fallback();
        }
        Optional<String> answer = pool.poll(resolved.poolKey());
        poolService.onConsume(raw, resolved.text());
        if (answer.isEmpty()) {
            return resolved.fallback();
        }
        Optional<PoolEntry> entry = poolService.findEntry(raw);
        if (entry.isPresent() && entry.get().hasVars()) {
            return VarSubstitutor.apply(answer.get(), entry.get().vars(), player);
        }
        return answer.get();
    }

    private String resolveCached(Player player, String raw) {
        ResolvedPrompt resolved = resolve(player, raw);
        if (!resolved.usable()) {
            return resolved.fallback();
        }

        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                resolved.overrides(),
                config.getSystemPrompt(),
                plugin.getKnowledgeBase(),
                resolved.knowledge());
        String key = httpClient.cacheKey(resolved.model(), resolved.text(), resolved.formatId(), prepared.cacheToken());
        return cache.get(key).orElseGet(() -> {
            if (config.canSendChatRequests()) {
                UUID playerId = player != null ? player.getUniqueId() : null;
                CompletionSupport.onComplete(
                        httpClient.requestAsync(resolved.text(), playerId, prepared.overrides(), resolved.ttl(), prepared.cacheToken()),
                        plugin.getLogger(),
                        "Background AI generation failed",
                        (ignored, error) -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.FINE, "Background AI generation failed", error);
                            }
                        });
            }
            return pool.peek(resolved.poolKey()).orElseGet(resolved::fallback);
        });
    }

    private ResolvedPrompt resolve(Player player, String raw) {
        return prompts.resolve(
                raw,
                config,
                template -> VarSubstitutor.resolve(player, template),
                ContextVariables.capture(player));
    }
}
