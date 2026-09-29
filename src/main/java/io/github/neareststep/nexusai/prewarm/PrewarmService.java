package io.github.neareststep.nexusai.prewarm;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.CompletionSupport;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
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
    private final PromptCatalog catalog;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean running;

    public PrewarmService(
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            ScheduledExecutorService scheduler,
            Logger logger
    ) {
        this(config, cache, httpClient, scheduler, logger, PromptCatalog.empty());
    }

    public PrewarmService(
            PluginConfig config,
            AiCache cache,
            AiHttpClient httpClient,
            ScheduledExecutorService scheduler,
            Logger logger,
            PromptCatalog catalog
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.catalog = catalog == null ? PromptCatalog.empty() : catalog;
    }

    public void start() {
        running = true;
        if (!config.isPrewarmEnabled() || !config.canSendRequests()) {
            return;
        }
        for (String configured : config.getPrewarmPrompts()) {
            Optional<NamedPrompt> named = catalog.find(configured);
            if (named.isPresent() && named.get().playerDependent()) {
                logger.info("Prewarm skips \"" + configured
                        + "\" until a player opens it, because its vars use PlaceholderAPI.");
                continue;
            }
            Prepared prepared = prepare(configured);
            if (prepared != null) {
                warmText(prepared);
            }
        }
    }

    public void scheduleRefresh() {
        if (!config.isPrewarmEnabled() || !config.canSendRequests()) {
            return;
        }
        long periodSeconds = Math.max(1L, config.getPrewarmRefreshBeforeTtl().toSeconds());
        refreshTask = scheduler.scheduleAtFixedRate(this::refreshStale, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public void warmForPlayer(String playerName) {
        Objects.requireNonNull(playerName, "playerName");
        if (!running || !config.isPrewarmEnabled() || !config.canSendRequests()) {
            return;
        }
        for (String template : config.getPrewarmPrompts()) {
            if (catalog.find(template).isPresent()) {
                Prepared prepared = prepare(template);
                if (prepared != null) {
                    warmText(prepared);
                }
                continue;
            }
            String prompt = template.replace("{player}", playerName);
            if (ContextVariables.usesBuiltIn(prompt, java.util.Set.of())) {
                continue;
            }
            warmText(new Prepared(prompt, GenerationOverrides.none().withFormat(config.defaultFormatId()), null));
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
        if (!running || !config.isPrewarmEnabled() || !config.canSendRequests()) {
            return;
        }
        for (String configured : config.getPrewarmPrompts()) {
            Prepared prepared = prepare(configured);
            if (prepared == null) {
                continue;
            }
            String key = httpClient.cacheKey(
                    prepared.overrides().model(config.getModel()),
                    prepared.text(),
                    prepared.overrides().formatOr(config.defaultFormatId()));
            if (cache.isFresh(key)) {
                continue;
            }
            warmText(prepared);
        }
    }

    private Prepared prepare(String configured) {
        Optional<NamedPrompt> named = catalog.find(configured);
        String text;
        GenerationOverrides overrides = GenerationOverrides.none();
        Duration ttl = null;
        if (named.isPresent()) {
            NamedPrompt prompt = named.get();
            if (prompt.playerDependent()) {
                return null;
            }
            text = prompt.render(value -> value);
            String format = prompt.format() == null ? config.defaultFormatId() : config.normalizeFormat(prompt.format());
            overrides = prompt.overrides().withFormat(format);
            ttl = prompt.ttl();
        } else {
            text = configured;
            overrides = GenerationOverrides.none().withFormat(config.defaultFormatId());
        }
        if (text == null || text.isBlank() || ContextVariables.usesBuiltIn(text, java.util.Set.of())) {
            return null;
        }
        return new Prepared(text, overrides, ttl);
    }

    private void warmText(Prepared prepared) {
        if (httpClient.isAdmissionBlocked(prepared.text())) {
            return;
        }
        CompletionSupport.onComplete(
                httpClient.requestAsync(prepared.text(), null, prepared.overrides(), prepared.ttl()),
                logger,
                "Prewarm request failed",
                (ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.FINE, "Prewarm request failed", error);
                    }
                });
    }

    private record Prepared(String text, GenerationOverrides overrides, Duration ttl) {
    }
}
