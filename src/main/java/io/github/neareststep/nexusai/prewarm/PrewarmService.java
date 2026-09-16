package io.github.neareststep.nexusai.prewarm;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Warms and refreshes the shared TTL cache for hologram-style placeholders.
 */
public final class PrewarmService {

    private final PluginConfig config;
    private final AiCache cache;
    private final AiHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean running;

    public PrewarmService(
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            ScheduledExecutorService scheduler,
            Logger logger
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void start() {
        running = true;
        if (!config.isPrewarmEnabled() || !config.hasApiKey()) {
            return;
        }
        for (String prompt : config.getPrewarmPrompts()) {
            warmPrompt(prompt);
        }
    }

    public void scheduleRefresh() {
        if (!config.isPrewarmEnabled() || !config.hasApiKey()) {
            return;
        }
        long periodSeconds = Math.max(1L, config.getPrewarmRefreshBeforeTtl().toSeconds());
        refreshTask = scheduler.scheduleAtFixedRate(this::refreshStale, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public void warmForPlayer(String playerName) {
        Objects.requireNonNull(playerName, "playerName");
        if (!running || !config.isPrewarmEnabled() || !config.hasApiKey()) {
            return;
        }
        for (String template : config.getPrewarmPrompts()) {
            String prompt = template.replace("{player}", playerName);
            warmPrompt(prompt);
        }
    }

    public void shutdown() {
        running = false;
        ScheduledFuture<?> task = refreshTask;
        if (task != null) {
            task.cancel(false);
            refreshTask = null;
        }
    }

    void refreshStale() {
        if (!running || !config.isPrewarmEnabled() || !config.hasApiKey()) {
            return;
        }
        for (String prompt : config.getPrewarmPrompts()) {
            if (prompt.contains("{player}")) {
                continue;
            }
            String key = httpClient.cacheKey(prompt);
            if (cache.isFresh(key)) {
                continue;
            }
            warmPrompt(prompt);
        }
    }

    private void warmPrompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.contains("{player}")) {
            return;
        }
        httpClient.requestAsync(prompt).whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.FINE, "Prewarm request failed", error);
            }
        });
    }
}
