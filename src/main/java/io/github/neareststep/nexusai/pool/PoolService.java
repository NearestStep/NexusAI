package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.PoolEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ConcurrentHashMap<String, AtomicBoolean> replenishing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PoolEntry> entriesByPrompt = new ConcurrentHashMap<>();
    private volatile boolean running;

    public PoolService(PluginConfig config, AiPool pool, AiHttpClient httpClient, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.logger = Objects.requireNonNull(logger, "logger");
        for (PoolEntry entry : config.getPoolEntries()) {
            entriesByPrompt.put(entry.prompt(), entry);
        }
    }

    public void start() {
        running = true;
        if (!config.isPoolEnabled() || !config.hasApiKey()) {
            return;
        }
        for (PoolEntry entry : config.getPoolEntries()) {
            replenish(entry.prompt());
        }
    }

    public void replenish(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!running || !config.isPoolEnabled() || !config.hasApiKey()) {
            return;
        }
        PoolEntry entry = entriesByPrompt.get(prompt);
        if (entry == null) {
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

        List<CompletableFuture<Void>> jobs = new ArrayList<>(needed);
        for (int i = 0; i < needed; i++) {
            jobs.add(httpClient.generateFreshAsync(prompt).handle((answer, error) -> {
                if (error != null) {
                    logger.log(Level.FINE, "Pool replenish failed for prompt", error);
                } else if (answer != null && !answer.isBlank()) {
                    pool.add(prompt, answer);
                }
                return null;
            }));
        }

        CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> flag.set(false));
    }

    public void onConsume(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        PoolEntry entry = entriesByPrompt.get(prompt);
        if (entry == null) {
            return;
        }
        if (pool.size(prompt) < entry.minThreshold()) {
            replenish(prompt);
        }
    }

    public void shutdown() {
        running = false;
    }
}
