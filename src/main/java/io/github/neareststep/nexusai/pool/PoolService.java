package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps configured {@link AiPool} queues topped up with unique AI answers.
 */
public final class PoolService {

    private final PluginConfig config;
    private final AiPool pool;
    private final AiHttpClient httpClient;
    private final Logger logger;
    private final PoolStore store;
    private final BiConsumer<Long, Runnable> retry;
    private final ConcurrentHashMap<String, AtomicBoolean> replenishing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> retryPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> duplicateStrikes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> duplicateLimitLogged = new ConcurrentHashMap<>();
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
        this.config = Objects.requireNonNull(config, "config");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.store = store == null ? PoolStore.disabled() : store;
        this.retry = retry;
        for (PoolEntry entry : config.getPoolEntries()) {
            entriesByPrompt.put(entry.prompt(), entry);
        }
    }

    public void start() {
        running = true;
        if (config.isPoolEnabled()) {
            store.load(pool, limits());
        }
        if (!config.isPoolEnabled() || !config.canSendRequests()) {
            return;
        }
        for (PoolEntry entry : config.getPoolEntries()) {
            replenish(entry.prompt());
        }
    }

    public void replenish(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!running || !config.isPoolEnabled() || !config.canSendRequests()) {
            return;
        }
        PoolEntry entry = entriesByPrompt.get(prompt);
        if (entry == null) {
            return;
        }
        int duplicateLimit = Math.max(8, entry.size() * 4);
        AtomicInteger strikes = duplicateStrikes.get(prompt);
        if (strikes != null && strikes.get() >= duplicateLimit) {
            return;
        }
        if (httpClient.isAdmissionBlocked(prompt)) {
            scheduleRetry(prompt, httpClient.admissionDelayMillis(prompt) + 25L);
            return;
        }

        AtomicBoolean flag = replenishing.computeIfAbsent(prompt, ignored -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return;
        }

        int needed = entry.size() - pool.size(prompt);
        if (needed <= 0) {
            flag.set(false);
            return;
        }

        AtomicInteger duplicates = new AtomicInteger();
        AtomicBoolean storedUnique = new AtomicBoolean();
        List<CompletableFuture<Void>> jobs = new ArrayList<>(needed);
        String httpPrompt = VarSubstitutor.appendVarsRules(prompt, entry.vars());
        for (int i = 0; i < needed; i++) {
            jobs.add(httpClient.generateFreshAsync(httpPrompt, prompt, entry.overrides()).handle((answer, error) -> {
                if (error != null) {
                    logger.log(Level.FINE, "Pool replenish failed for prompt", error);
                } else if (answer != null && !answer.isBlank()) {
                    if (pool.add(prompt, answer, isPersonalizedTemplate(answer, entry))) {
                        storedUnique.set(true);
                        duplicateStrikes.remove(prompt);
                        duplicateLimitLogged.remove(prompt);
                        store.markDirty(pool, limits());
                    } else {
                        duplicates.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    flag.set(false);
                    if (!running || pool.size(prompt) >= entry.size()) {
                        return;
                    }
                    if (httpClient.isAdmissionBlocked(prompt)) {
                        scheduleRetry(prompt, httpClient.admissionDelayMillis(prompt) + 25L);
                        return;
                    }
                    int repeated = duplicates.get();
                    if (repeated <= 0) {
                        return;
                    }
                    if (storedUnique.get()) {
                        scheduleRetry(prompt, Math.max(50L, config.getErrorBackoffInitialSeconds() * 1000L));
                        return;
                    }
                    int strikeCount = duplicateStrikes.computeIfAbsent(prompt, key -> new AtomicInteger()).addAndGet(repeated);
                    int limit = Math.max(8, entry.size() * 4);
                    if (strikeCount >= limit) {
                        if (duplicateLimitLogged.putIfAbsent(prompt, Boolean.TRUE) == null) {
                            logger.warning("Stopped refilling pool for \"" + prompt + "\" after " + strikeCount
                                    + " duplicate answers. A different answer or /nai reload will try again.");
                        }
                        return;
                    }
                    scheduleRetry(prompt, Math.max(50L, config.getErrorBackoffInitialSeconds() * 1000L));
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

    private void scheduleRetry(String prompt, long delayMillis) {
        if (retry == null || !running) {
            return;
        }
        AtomicBoolean pending = retryPending.computeIfAbsent(prompt, ignored -> new AtomicBoolean());
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        retry.accept(Math.max(50L, delayMillis), () -> {
            pending.set(false);
            if (running) {
                replenish(prompt);
            }
        });
    }

    public void onConsume(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        PoolEntry entry = entriesByPrompt.get(prompt);
        if (entry == null) {
            return;
        }
        store.markDirty(pool, limits());
        if (pool.size(prompt) < entry.minThreshold()) {
            replenish(prompt);
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

    private Map<String, Integer> limits() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (PoolEntry entry : entriesByPrompt.values()) {
            limits.put(entry.prompt(), entry.size());
        }
        return limits;
    }
}
