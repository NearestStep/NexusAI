package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cache-aware async facade over {@link AiProvider} with in-flight deduplication.
 */
public final class AiHttpClient {

    private final AiCache cache;
    private final AiProvider provider;
    private final PluginConfig config;
    private final Logger logger;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public AiHttpClient(AiCache cache, AiProvider provider, PluginConfig config, Logger logger) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public CompletableFuture<String> requestAsync(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        String key = cacheKey(prompt);

        return cache.get(key)
                .map(CompletableFuture::completedFuture)
                .orElseGet(() -> {
                    if (!config.hasApiKey()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("NexusAI API key is not configured"));
                    }
                    return inFlight.computeIfAbsent(key, this::startRequest);
                });
    }

    /**
     * Fresh completion that never reads or writes the shared TTL cache.
     * Used by response pools so each fill stays unique.
     */
    public CompletableFuture<String> generateFreshAsync(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!config.hasApiKey()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("NexusAI API key is not configured"));
        }
        return provider.complete(prompt);
    }

    private CompletableFuture<String> startRequest(String key) {
        String prompt = promptFromKey(key);
        CompletableFuture<String> future = provider.complete(prompt);
        future.whenComplete((value, error) -> {
            try {
                if (error == null && value != null && !value.isBlank()) {
                    cache.put(key, value);
                } else if (error != null) {
                    logger.log(Level.FINE, "AI request failed for key hash", error);
                }
            } finally {
                inFlight.remove(key, future);
            }
        });
        return future;
    }

    public String cacheKey(String prompt) {
        return config.getModel() + '\u0000' + prompt;
    }

    private static String promptFromKey(String key) {
        int sep = key.indexOf('\u0000');
        return sep >= 0 ? key.substring(sep + 1) : key;
    }
}
