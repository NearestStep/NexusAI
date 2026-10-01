package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter RETRY_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        return requestAsync(prompt, playerId, GenerationOverrides.none(), null);
    }

    /**
     * @param cacheTtl per-entry TTL, or {@code null} to use the cache default
     */
    public CompletableFuture<String> requestAsync(
            String prompt,
            UUID playerId,
            GenerationOverrides overrides,
            Duration cacheTtl
    ) {
        Objects.requireNonNull(prompt, "prompt");
        return requestAsync(prompt, playerId, overrides, cacheTtl, "");
    }

    /**
     * @param knowledgeHash cache-key fragment for injected knowledge, or empty when the prompt has none
     */
    public CompletableFuture<String> requestAsync(
            String prompt,
            UUID playerId,
            GenerationOverrides overrides,
            Duration cacheTtl,
            String knowledgeHash
    ) {
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        String key = cacheKey(
                effective.model(config.getModel()),
                prompt,
                effective.formatOr(config.defaultFormatId()),
                knowledgeHash);
        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached.get());
        }
        return startShared(key, prompt, prompt, playerId, false, true, effective, cacheTtl);
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
        if (!config.canSendChatRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(null, admissionKey, false);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(admissionKey);
        CompletableFuture<String> created = new CompletableFuture<>();
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, created, false, null, effective, null, true, false);
        return created;
    }

    /**
     * One live request for {@code /nai test}. Skips pause and backoff so an admin can probe,
     * but still spends a server rate-limit slot. Does not read or write the TTL cache.
     * A probe does not clear, start, or extend a provider pause, and it does not start or
     * extend a model-queue cooldown. A daily cap still blocks the call.
     */
    public CompletableFuture<String> testAsync(String prompt) {
        return testAsync(prompt, GenerationOverrides.none());
    }

    public CompletableFuture<String> testAsync(String prompt, GenerationOverrides overrides) {
        Objects.requireNonNull(prompt, "prompt");
        if (!config.canSendChatRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(RateLimiter.SERVER_SENTINEL, prompt, true);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(prompt);
        CompletableFuture<String> created = new CompletableFuture<>();
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        dispatch(prompt, prompt, pauseStamp, failureEpoch, created, false, null, effective, null, false, true);
        return created;
    }

    public boolean isAdmissionBlocked(String admissionKey) {
        return gate.isBlocked(admissionKey);
    }

    /**
     * Clears per-prompt backoff, including an empty-reply ladder.
     * Used when configuration is reloaded.
     */
    public void resetBackoff() {
        gate.resetBackoff();
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
            GenerationOverrides overrides,
            Duration cacheTtl
    ) {
        CompletableFuture<String> existing = inFlight.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        if (!config.canSendChatRequests()) {
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
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, created, writeCache, cacheKey, overrides, cacheTtl, true, false);
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
            Duration cacheTtl,
            boolean clearPause,
            boolean ignoreCooldown
    ) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CompletableFuture<ModelAnswer> upstream;
        try {
            upstream = provider.answer(prompt, effective, ignoreCooldown);
        } catch (RuntimeException e) {
            finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, null, e, writeCache, cacheTtl, clearPause);
            return;
        }
        upstream.whenComplete((answer, error) -> {
            try {
                String value = answer == null ? null : answer.text();
                finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, value, error, writeCache,
                        effectiveCacheTtl(cacheTtl, answer), clearPause);
            } catch (Throwable thrown) {
                logger.log(Level.WARNING, "AI completion handler failed", thrown);
                created.completeExceptionally(thrown);
            }
        });
    }

    /**
     * A reply may carry its own TTL. Use that TTL when it is shorter than the prompt TTL or
     * {@code cache.ttl}. A length-truncated reply does not carry one, so it keeps the normal TTL.
     */
    private Duration effectiveCacheTtl(Duration requested, ModelAnswer answer) {
        if (answer == null || answer.cacheTtl() == null) {
            return requested;
        }
        Duration normal = requested != null ? requested : config.getCacheTtl();
        return answer.cacheTtl().compareTo(normal) < 0 ? answer.cacheTtl() : normal;
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
            Duration cacheTtl,
            boolean clearPause
    ) {
        try {
            if (error == null && (value == null || value.isBlank() || PlayerInput.stripSectionSigns(value, config.allowMarkup()).isBlank())) {
                error = new AiRequestException(AiErrorKind.EMPTY_REPLY, 0, PlayerInput.EMPTY_REPLY, null);
                value = null;
            }
            if (error == null && value != null && !value.isBlank()) {
                if (writeCache && cacheKey != null) {
                    if (cacheTtl != null) {
                        cache.put(cacheKey, value, cacheTtl);
                    } else {
                        cache.put(cacheKey, value);
                    }
                }
                gate.recordSuccess(admissionKey, pauseStamp, failureEpoch, clearPause);
                created.complete(value);
            } else if (error != null || value == null || value.isBlank()) {
                if (AiErrors.localMissingKey(error)) {
                    created.completeExceptionally(AiErrors.unwrap(error));
                    return;
                }
                Throwable failure = error != null
                        ? error
                        : new AiRequestException(AiErrorKind.OTHER, 0, "OpenAI response missing choices/message/content", null);
                AiErrorKind kind = AiErrors.classify(failure);
                if (kind != AiErrorKind.LOCAL_LIMIT && kind != AiErrorKind.REJECTED) {
                    AiRequestException typed = AiErrors.find(failure);
                    long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
                    gate.recordFailure(admissionKey, kind, retryAfter, clearPause);
                    diagnostics.report(kind, failureDetail(admissionKey, kind, failure), gate.isPaused());
                }
                if (kind == AiErrorKind.REJECTED) {
                    logger.log(Level.FINE, "Rejected model answer: {0}", AiErrors.detail(failure));
                } else {
                    logger.log(Level.FINE, "AI request failed", failure);
                }
                created.completeExceptionally(AiErrors.unwrap(failure));
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "AI completion handler failed", e);
            created.completeExceptionally(e);
        } finally {
            if (cacheKey != null) {
                inFlight.remove(cacheKey, created);
            }
        }
    }

    private String failureDetail(String admissionKey, AiErrorKind kind, Throwable failure) {
        if (kind != AiErrorKind.EMPTY_REPLY) {
            return AiErrors.detail(failure);
        }
        long until = gate.blockedUntilMillis(admissionKey);
        if (until <= 0L) {
            return null;
        }
        String when = Instant.ofEpochMilli(until).atZone(ZoneId.systemDefault()).format(RETRY_CLOCK);
        return "Retry after " + when + ".";
    }

    private static CompletableFuture<String> rejected(String reason) {
        return CompletableFuture.failedFuture(new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, reason, null));
    }

    public String cacheKey(String prompt) {
        return cacheKey(config.getModel(), prompt);
    }

    public String cacheKey(String model, String prompt) {
        return cacheKey(model, prompt, config.defaultFormatId());
    }

    public String cacheKey(String model, String prompt, String format) {
        return cacheKey(model, prompt, format, "");
    }

    /**
     * Knowledge text is not part of the user prompt, so its hash is a separate cache-key field.
     * An empty hash keeps the historical key used by prompts that attach no knowledge.
     */
    public String cacheKey(String model, String prompt, String format, String knowledgeHash) {
        String effectiveModel = model == null || model.isBlank() ? config.getModel() : model;
        String effectiveFormat = config.normalizeFormat(format);
        String base = effectiveModel + '\u0000' + effectiveFormat + '\u0000' + PlayerInput.KEY_VERSION + '\u0000' + prompt;
        if (knowledgeHash == null || knowledgeHash.isBlank()) {
            return base;
        }
        return effectiveModel + '\u0000' + effectiveFormat + '\u0000' + PlayerInput.KEY_VERSION
                + '\u0000' + knowledgeHash + '\u0000' + prompt;
    }

    /**
     * Shared admission for dialogue calls. Placeholder requests keep using {@link #requestAsync}.
     */
    public Optional<String> tryAdmit(UUID playerId, String admissionKey) {
        return gate.tryAdmit(playerId, admissionKey, false);
    }

    public void recordAdmissionSuccess(String admissionKey) {
        gate.recordSuccess(admissionKey, gate.pauseStamp(), gate.failureEpoch(admissionKey), true);
    }

    public void recordAdmissionFailure(String admissionKey, Throwable error) {
        if (AiErrors.localMissingKey(error)) {
            return;
        }
        AiErrorKind kind = AiErrors.classify(error);
        if (kind == AiErrorKind.LOCAL_LIMIT || kind == AiErrorKind.REJECTED) {
            return;
        }
        AiRequestException typed = AiErrors.find(error);
        long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
        gate.recordFailure(admissionKey, kind, retryAfter, true);
        diagnostics.report(kind, AiErrors.detail(error), gate.isPaused());
    }

    public io.github.neareststep.nexusai.ai.KeyRing sharedRing(String providerId) {
        if (provider instanceof RoutingProvider routing) {
            return routing.sharedRing(providerId);
        }
        return new io.github.neareststep.nexusai.ai.KeyRing(java.util.List.of());
    }
}
