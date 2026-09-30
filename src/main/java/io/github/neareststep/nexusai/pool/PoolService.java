package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps configured {@link AiPool} queues topped up with unique AI answers.
 * Named prompts are stored under the resolved text so player-specific vars do not share a queue.
 */
public final class PoolService {

    private final PluginConfig config;
    private final AiPool pool;
    private final AiHttpClient httpClient;
    private final Logger logger;
    private final PoolStore store;
    private final BiConsumer<Long, Runnable> retry;
    private final PromptCatalog catalog;
    private final KnowledgeBase knowledge;
    private final ConcurrentHashMap<String, AtomicBoolean> replenishing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> retryPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> duplicateStrikes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> duplicateLimitLogged = new ConcurrentHashMap<>();
    private final Set<String> ambiguousLogged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, PoolEntry> entriesByPrompt = new ConcurrentHashMap<>();
    private volatile boolean running;

    public PoolService(PluginConfig config, AiPool pool, AiHttpClient httpClient, Logger logger) {
        this(config, pool, httpClient, logger, PoolStore.disabled());
    }

    public PoolService(PluginConfig config, AiPool pool, AiHttpClient httpClient, Logger logger, PoolStore store) {
        this(config, pool, httpClient, logger, store, null);
    }

    /**
     * @param retry schedules another refill after backoff or a duplicate answer; {@code null} disables it
     */
    public PoolService(
            PluginConfig config,
            AiPool pool,
            AiHttpClient httpClient,
            Logger logger,
            PoolStore store,
            BiConsumer<Long, Runnable> retry
    ) {
        this(config, pool, httpClient, logger, store, retry, PromptCatalog.empty(), KnowledgeBase.empty());
    }

    public PoolService(
            PluginConfig config,
            AiPool pool,
            AiHttpClient httpClient,
            Logger logger,
            PoolStore store,
            BiConsumer<Long, Runnable> retry,
            PromptCatalog catalog
    ) {
        this(config, pool, httpClient, logger, store, retry, catalog, KnowledgeBase.empty());
    }

    public PoolService(
            PluginConfig config,
            AiPool pool,
            AiHttpClient httpClient,
            Logger logger,
            PoolStore store,
            BiConsumer<Long, Runnable> retry,
            PromptCatalog catalog,
            KnowledgeBase knowledge
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.store = store == null ? PoolStore.disabled() : store;
        this.retry = retry;
        this.catalog = catalog == null ? PromptCatalog.empty() : catalog;
        this.knowledge = knowledge == null ? KnowledgeBase.empty() : knowledge;
        for (PoolEntry entry : config.getPoolEntries()) {
            entriesByPrompt.put(entry.prompt(), entry);
        }
    }

    public void start() {
        running = true;
        if (config.isPoolEnabled()) {
            warnStaleNamedRows();
            store.load(pool, staticLimits(), this::dynamicLimit);
        }
        if (!config.isPoolEnabled() || !config.canSendChatRequests()) {
            return;
        }
        for (PoolEntry entry : config.getPoolEntries()) {
            String text = catalog.staticText(entry.prompt());
            if (text == null) {
                logger.info("Pool entry \"" + entry.prompt()
                        + "\" uses player-specific prompt vars and refills when a player reads it.");
                continue;
            }
            replenish(entry.prompt(), text);
        }
    }

    public void replenish(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        String text = catalog.staticText(prompt);
        if (text == null) {
            return;
        }
        replenish(prompt, text);
    }

    private void replenish(String configuredPrompt, String poolKey) {
        Objects.requireNonNull(configuredPrompt, "configuredPrompt");
        Objects.requireNonNull(poolKey, "poolKey");
        String memory = memoryKey(configuredPrompt, poolKey);
        if (!running || !config.isPoolEnabled() || !config.canSendChatRequests()) {
            return;
        }
        PoolEntry entry = entriesByPrompt.get(configuredPrompt);
        if (entry == null) {
            return;
        }
        int duplicateLimit = Math.max(8, entry.size() * 4);
        AtomicInteger strikes = duplicateStrikes.get(memory);
        if (strikes != null && strikes.get() >= duplicateLimit) {
            return;
        }
        if (httpClient.isAdmissionBlocked(poolKey)) {
            scheduleRetry(configuredPrompt, poolKey, httpClient.admissionDelayMillis(poolKey) + 25L);
            return;
        }

        AtomicBoolean flag = replenishing.computeIfAbsent(memory, ignored -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return;
        }

        int needed = entry.size() - pool.size(memory);
        if (needed <= 0) {
            flag.set(false);
            return;
        }

        AtomicInteger duplicates = new AtomicInteger();
        AtomicBoolean storedUnique = new AtomicBoolean();
        List<CompletableFuture<Void>> jobs = new ArrayList<>(needed);
        String httpPrompt = VarSubstitutor.appendVarsRules(poolKey, entry.vars());
        GenerationOverrides overrides = overridesFor(configuredPrompt);
        for (int i = 0; i < needed; i++) {
            jobs.add(httpClient.generateFreshAsync(httpPrompt, poolKey, overrides).handle((answer, error) -> {
                try {
                    if (error != null) {
                        logger.log(Level.FINE, "Pool replenish failed for prompt", error);
                    } else if (answer != null && !answer.isBlank()) {
                        if (pool.add(memory, answer, isPersonalizedTemplate(answer, entry))) {
                            storedUnique.set(true);
                            duplicateStrikes.remove(memory);
                            duplicateLimitLogged.remove(memory);
                            store.markDirty(pool, limits());
                        } else {
                            duplicates.incrementAndGet();
                        }
                    }
                } catch (Throwable thrown) {
                    logger.log(Level.WARNING, "Pool replenish handler failed", thrown);
                }
                return null;
            }));
        }

        CompletionSupport.onComplete(
                CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)),
                logger,
                "Pool replenish completion failed",
                (ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Pool replenish completion failed", error);
                    }
                    flag.set(false);
                    if (!running || pool.size(memory) >= entry.size()) {
                        return;
                    }
                    if (httpClient.isAdmissionBlocked(poolKey)) {
                        scheduleRetry(configuredPrompt, poolKey, httpClient.admissionDelayMillis(poolKey) + 25L);
                        return;
                    }
                    int repeated = duplicates.get();
                    if (repeated <= 0) {
                        return;
                    }
                    if (storedUnique.get()) {
                        scheduleRetry(configuredPrompt, poolKey, Math.max(50L, config.getErrorBackoffInitialSeconds() * 1000L));
                        return;
                    }
                    int strikeCount = duplicateStrikes.computeIfAbsent(memory, key -> new AtomicInteger()).addAndGet(repeated);
                    int limit = Math.max(8, entry.size() * 4);
                    if (strikeCount >= limit) {
                        if (duplicateLimitLogged.putIfAbsent(memory, Boolean.TRUE) == null) {
                            logger.warning("Stopped refilling pool for \"" + poolKey + "\" after " + strikeCount
                                    + " duplicate answers. A different answer or /nai reload will try again.");
                        }
                        return;
                    }
                    scheduleRetry(configuredPrompt, poolKey, Math.max(50L, config.getErrorBackoffInitialSeconds() * 1000L));
                });
    }

    /**
     * A repeated template such as {@code Hello {player_name}!} is one delivery per player, so it may fill {@code size}.
     * Finished text with none of this entry's tokens stays unique.
     */
    private static boolean isPersonalizedTemplate(String answer, PoolEntry entry) {
        Map<String, String> vars = entry.vars();
        if (vars.isEmpty()) {
            return false;
        }
        for (String key : vars.keySet()) {
            if (key != null && !key.isBlank() && answer.contains('{' + key + '}')) {
                return true;
            }
        }
        return false;
    }

    private GenerationOverrides overridesFor(String configuredPrompt) {
        PoolEntry entry = entriesByPrompt.get(configuredPrompt);
        GenerationOverrides entryOverrides = entry == null ? GenerationOverrides.none() : entry.overrides();
        NamedPrompt named = catalog.find(configuredPrompt).orElse(null);
        GenerationOverrides merged = named == null ? entryOverrides : named.overrides().overlay(entryOverrides);
        merged = merged.withFormat(formatId(configuredPrompt));
        if (merged.fallbackModel() == null) {
            FallbackModel fallback = named != null && named.fallbackModel() != null
                    ? named.fallbackModel()
                    : config.fallbackModel();
            if (fallback != null && fallback.configured()) {
                merged = merged.withFallbackModel(fallback.provider(), fallback.model());
            }
        }
        return KnowledgeComposer.prepare(
                merged,
                config.getSystemPrompt(),
                knowledge,
                named == null ? List.of() : named.knowledge()
        ).overrides();
    }

    private String formatId(String configuredPrompt) {
        NamedPrompt named = catalog.find(configuredPrompt).orElse(null);
        if (named != null && named.format() != null) {
            return config.normalizeFormat(named.format());
        }
        return config.defaultFormatId();
    }

    private String memoryKey(String configuredPrompt, String text) {
        return PoolKeys.memory(formatId(configuredPrompt), text);
    }

    private void scheduleRetry(String configuredPrompt, String poolKey, long delayMillis) {
        if (retry == null || !running) {
            return;
        }
        AtomicBoolean pending = retryPending.computeIfAbsent(poolKey, ignored -> new AtomicBoolean());
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        retry.accept(Math.max(50L, delayMillis), () -> {
            pending.set(false);
            if (running) {
                replenish(configuredPrompt, poolKey);
            }
        });
    }

    public void onConsume(String prompt) {
        onConsume(prompt, prompt);
    }

    /**
     * @param configuredPrompt pool entry prompt, which may be a named-prompt id
     * @param poolKey          resolved text actually stored in the pool
     */
    public void onConsume(String configuredPrompt, String poolKey) {
        Objects.requireNonNull(configuredPrompt, "configuredPrompt");
        Objects.requireNonNull(poolKey, "poolKey");
        PoolEntry entry = entriesByPrompt.get(configuredPrompt);
        if (entry == null) {
            return;
        }
        store.markDirty(pool, limits());
        if (pool.size(memoryKey(configuredPrompt, poolKey)) < entry.minThreshold()) {
            replenish(configuredPrompt, poolKey);
        }
    }

    public Optional<PoolEntry> findEntry(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        return Optional.ofNullable(entriesByPrompt.get(prompt));
    }

    public void shutdown() {
        running = false;
        store.flush(pool, limits());
    }

    private void warnStaleNamedRows() {
        for (String saved : store.savedPrompts()) {
            if (catalog.find(saved).isEmpty()) {
                continue;
            }
            String resolved = catalog.staticText(saved);
            if (resolved == null || !saved.equals(resolved)) {
                logger.warning("pool.yml saved answers under the prompt id \"" + saved
                        + "\". Named prompts are stored under the resolved text, so those rows were not loaded.");
            }
        }
    }

    private Map<String, Integer> staticLimits() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (PoolEntry entry : entriesByPrompt.values()) {
            String text = catalog.staticText(entry.prompt());
            if (text != null) {
                limits.put(memoryKey(entry.prompt(), text), entry.size());
            }
        }
        return limits;
    }

    private Integer dynamicLimit(String saved) {
        PoolKeys.Parsed parsed = PoolKeys.parse(saved);
        PoolEntry match = null;
        for (PoolEntry entry : entriesByPrompt.values()) {
            NamedPrompt named = catalog.find(entry.prompt()).orElse(null);
            if (named == null || !named.playerDependent() || !named.matchesResolved(parsed.prompt())) {
                continue;
            }
            String expected = named.format() == null ? config.defaultFormatId() : config.normalizeFormat(named.format());
            if (!expected.equals(io.github.neareststep.nexusai.config.FormatPresets.normalize(parsed.format()))) {
                continue;
            }
            if (match != null) {
                String key = match.prompt() + "\n" + entry.prompt();
                if (ambiguousLogged.add(key)) {
                    logger.warning("Saved pool prompt matches both \"" + match.prompt() + "\" and \""
                            + entry.prompt() + "\". It was kept for \"" + match.prompt() + "\".");
                }
                break;
            }
            match = entry;
        }
        return match == null ? null : match.size();
    }

    private Map<String, Integer> limits() {
        Map<String, Integer> limits = new LinkedHashMap<>(staticLimits());
        for (String live : pool.prompts()) {
            if (limits.containsKey(live)) {
                continue;
            }
            Integer dynamic = dynamicLimit(live);
            if (dynamic != null) {
                limits.put(live, dynamic);
            }
        }
        return limits;
    }
}
