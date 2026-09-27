package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cache-aware async facade over {@link AiProvider} with in-flight deduplication.
 * Every provider call spends a shared {@link RateLimiter} slot and honors backoff/pause.
 */
public final class AiHttpClient {

    private final AiCache cache;
    private final AiProvider provider;
    private final PluginConfig config;
    private final RequestGate gate;
    private final AiDiagnostics diagnostics;
    private final Logger logger;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public AiHttpClient(
            AiCache cache,
            AiProvider provider,
            PluginConfig config,
            RequestGate gate,
            AiDiagnostics diagnostics,
            Logger logger
    ) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Test/helper constructor with permissive admission and quiet diagnostics.
     */
    public AiHttpClient(AiCache cache, AiProvider provider, PluginConfig config, Logger logger) {
        this(cache, provider, config, RequestGate.permissive(), new AiDiagnostics(logger, java.time.Duration.ofSeconds(30)), logger);
    }

    public CompletableFuture<String> requestAsync(String prompt) {
        return requestAsync(prompt, null);
    }

    /**
     * @param playerId player UUID for per-player limits, or {@code null} for server counters only
     */
    public CompletableFuture<String> requestAsync(String prompt, UUID playerId) {
        Objects.requireNonNull(prompt, "prompt");
        String key = cacheKey(prompt);
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }
        return startShared(key, prompt, prompt, playerId, false, true, GenerationOverrides.none());
    }

    /**
     * Fresh completion that never reads or writes the shared TTL cache.
     * Used by response pools so each fill stays unique.
     */
    public CompletableFuture<String> generateFreshAsync(String prompt) {
        return generateFreshAsync(prompt, prompt);
    }

    /**
     * @param admissionKey stable id for backoff (the pool prompt), which may differ from the HTTP prompt
     */
    public CompletableFuture<String> generateFreshAsync(String prompt, String admissionKey) {
        return generateFreshAsync(prompt, admissionKey, GenerationOverrides.none());
    }

    public CompletableFuture<String> generateFreshAsync(
            String prompt,
            String admissionKey,
            GenerationOverrides overrides
    ) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(admissionKey, "admissionKey");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        if (!config.canSendRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(null, admissionKey, false);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(admissionKey);
        CompletableFuture<String> created = new CompletableFuture<>();
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, created, false, null, effective, true);
        return created;
    }

    /**
     * One live request for {@code /nai test}. Skips pause and backoff so an admin can probe,
     * but still spends a server rate-limit slot. Does not read or write the TTL cache.
     * A successful probe does not clear a provider pause.
     */
    public CompletableFuture<String> testAsync(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (!config.canSendRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(RateLimiter.SERVER_SENTINEL, prompt, true);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(prompt);
        CompletableFuture<String> created = new CompletableFuture<>();
        dispatch(prompt, prompt, pauseStamp, failureEpoch, created, false, null, GenerationOverrides.none(), false);
        return created;
    }

    public boolean isAdmissionBlocked(String admissionKey) {
        return gate.isBlocked(admissionKey);
    }

    public long admissionDelayMillis(String admissionKey) {
        return gate.blockedForMillis(admissionKey);
    }

    public boolean isProviderPaused() {
        return gate.isPaused();
    }

    public AiErrorKind pauseKind() {
        return gate.pauseKind();
    }

    public long pauseRemainingSeconds() {
        long millis = gate.pauseRemainingMillis();
        if (millis <= 0L) {
            return 0L;
        }
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    public String lastErrorText() {
        return diagnostics.lastError();
    }

    private CompletableFuture<String> startShared(
            String cacheKey,
            String prompt,
            String admissionKey,
            UUID playerId,
            boolean bypassBackoffAndPause,
            boolean writeCache,
            GenerationOverrides overrides
    ) {
        CompletableFuture<String> existing = inFlight.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        if (!config.canSendRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        CompletableFuture<String> created = new CompletableFuture<>();
        CompletableFuture<String> raced = inFlight.putIfAbsent(cacheKey, created);
        if (raced != null) {
            return raced;
        }
        Optional<String> rejection = gate.tryAdmit(playerId, admissionKey, bypassBackoffAndPause);
        if (rejection.isPresent()) {
            inFlight.remove(cacheKey, created);
            created.completeExceptionally(new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejection.get(), null));
            return created;
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(admissionKey);
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, created, writeCache, cacheKey, overrides, true);
        return created;
    }

    private void dispatch(
            String prompt,
            String admissionKey,
            long pauseStamp,
            long failureEpoch,
            CompletableFuture<String> created,
            boolean writeCache,
            String cacheKey,
            GenerationOverrides overrides,
            boolean clearPause
    ) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CompletableFuture<String> upstream;
        try {
            upstream = provider.complete(prompt, effective);
        } catch (RuntimeException e) {
            finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, null, e, writeCache, clearPause);
            return;
        }
        upstream.whenComplete((value, error) ->
                finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, value, error, writeCache, clearPause));
    }

    private void finish(
            String cacheKey,
            String admissionKey,
            long pauseStamp,
            long failureEpoch,
            CompletableFuture<String> created,
            String value,
            Throwable error,
            boolean writeCache,
            boolean clearPause
    ) {
        try {
            if (error == null && value != null && !value.isBlank()) {
                if (writeCache && cacheKey != null) {
                    cache.put(cacheKey, value);
                }
                gate.recordSuccess(admissionKey, pauseStamp, failureEpoch, clearPause);
                created.complete(value);
            } else if (error != null || value == null || value.isBlank()) {
                Throwable failure = error != null
                        ? error
                        : new AiRequestException(AiErrorKind.OTHER, 0, "OpenAI response missing choices/message/content", null);
                AiErrorKind kind = AiErrors.classify(failure);
                if (kind != AiErrorKind.LOCAL_LIMIT) {
                    AiRequestException typed = AiErrors.find(failure);
                    long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
                    gate.recordFailure(admissionKey, kind, retryAfter);
                    diagnostics.report(kind, AiErrors.detail(failure));
                }
                logger.log(Level.FINE, "AI request failed", failure);
                created.completeExceptionally(AiErrors.unwrap(failure));
            }
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
        } finally {
            if (cacheKey != null) {
                inFlight.remove(cacheKey, created);
            }
        }
    }

    private static CompletableFuture<String> rejected(String reason) {
        return CompletableFuture.failedFuture(new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, reason, null));
    }

    public String cacheKey(String prompt) {
        return config.getModel() + '\u0000' + prompt;
    }
}
