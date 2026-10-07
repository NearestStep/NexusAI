package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.NexusAI;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.context.CachedContextCoordinator;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
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
        NamedPrompt named = contextPrompt(player, resolved);
        if (named == null) {
            return cachedLookup(player, raw, resolved, resolved.text(), prepared);
        }
        CachedContextCoordinator coordinator = plugin.getContextCoordinator();
        if (coordinator == null || plugin.getContextService() == null) {
            return cachedLookup(player, raw, resolved, resolved.text(), prepared);
        }
        org.bukkit.Location location = player.getLocation();
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        ContextRequest request = new ContextRequest(
                player.getUniqueId(),
                player.getName(),
                world,
                named.id(),
                ContextRequest.Purpose.PLACEHOLDER);
        CachedContextCoordinator.Decision decision = coordinator.decide(
                player.getUniqueId(),
                named.id(),
                resolved.text(),
                true,
                System.currentTimeMillis(),
                () -> plugin.getContextService().collect(request, named.context()),
                text -> startBackground(player, raw, resolved, text, prepared));
        if (decision.phase() == CachedContextCoordinator.Phase.PENDING) {
            return pool.peek(resolved.poolKey()).orElseGet(resolved::fallback);
        }
        return cachedLookup(player, raw, resolved, decision.promptText(), prepared);
    }

    /**
     * Named prompts with {@code context:} and an online player. Literal placeholders, the console,
     * and {@code context.enabled: false} stay on the historical path.
     */
    private NamedPrompt contextPrompt(Player player, ResolvedPrompt resolved) {
        if (player == null || resolved.id() == null || resolved.id().isBlank() || !config.contextSettings().enabled()) {
            return null;
        }
        NamedPrompt named = prompts.find(resolved.id()).orElse(null);
        if (named == null || !named.context().active()) {
            return null;
        }
        return named;
    }

    private String cachedLookup(
            Player player,
            String raw,
            ResolvedPrompt resolved,
            String promptText,
            KnowledgeComposer.Prepared prepared
    ) {
        String key = httpClient.cacheKey(resolved.model(), promptText, resolved.formatId(), prepared.cacheToken());
        return cache.get(key).orElseGet(() -> {
            if (config.canSendChatRequests()) {
                startBackground(player, raw, resolved, promptText, prepared);
            }
            return pool.peek(resolved.poolKey()).orElseGet(resolved::fallback);
        });
    }

    private void startBackground(
            Player player,
            String raw,
            ResolvedPrompt resolved,
            String promptText,
            KnowledgeComposer.Prepared prepared
    ) {
        UUID playerId = player != null ? player.getUniqueId() : null;
        CallTrace trace = placeholderTrace(playerId, resolved == null ? null : resolved.id());
        CompletionSupport.onComplete(
                httpClient.requestAsync(
                        promptText,
                        playerId,
                        prepared.overrides().withNoticeId(noticeId(resolved, raw)),
                        resolved.ttl(),
                        prepared.cacheToken(),
                        trace,
                        PlaceholderAdmission.key(resolved, promptText)),
                plugin.getLogger(),
                "Background AI generation failed",
                (ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.FINE, "Background AI generation failed", error);
                    }
                });
    }

    /**
     * A cached placeholder. {@code promptId} is the named-prompt id, or empty for a literal
     * argument. The rendered text is not the id.
     */
    static CallTrace placeholderTrace(UUID playerId, String promptId) {
        return CallTrace.start(RequestOrigin.PLACEHOLDER, playerId, promptId == null ? "" : promptId, "");
    }

    /**
     * Named prompts key on their id. A literal placeholder keys on the argument before
     * {@code {player}} and the other built-ins are filled in.
     */
    private static String noticeId(ResolvedPrompt resolved, String raw) {
        if (resolved.id() != null && !resolved.id().isBlank()) {
            return resolved.id();
        }
        return raw == null ? "" : raw;
    }

    private ResolvedPrompt resolve(Player player, String raw) {
        return prompts.resolve(
                raw,
                config,
                template -> VarSubstitutor.resolve(player, template),
                ContextVariables.capture(player));
    }
}
