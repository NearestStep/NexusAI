package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.budget.QuotaPolicy;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import org.jetbrains.annotations.ApiStatus;

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
    private final ConcurrentHashMap<String, CompletableFuture<SharedCompletion>> inFlight = new ConcurrentHashMap<>();
    private volatile QuotaPolicy quotas;

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

    /** Server, player, and consumer caps for placeholder, pool, prewarm, and {@code /nai test}. */
    public void quotas(QuotaPolicy policy) {
        this.quotas = policy;
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
        return requestAsync(prompt, playerId, overrides, cacheTtl, knowledgeHash, null);
    }

    /**
     * @param trace entrance that asked for this completion, or null for a caller that has none
     */
    public CompletableFuture<String> requestAsync(
            String prompt,
            UUID playerId,
            GenerationOverrides overrides,
            Duration cacheTtl,
            String knowledgeHash,
            CallTrace trace
    ) {
        return requestAsync(prompt, playerId, overrides, cacheTtl, knowledgeHash, trace, null);
    }

    /**
     * @param admissionKey backoff id. Blank keeps the rendered prompt, which is what a literal
     *                     placeholder and prewarm already use. A named prompt passes its id so it
     *                     shares backoff with {@code generate} for that prompt. An inline template
     *                     stays {@code api:<owner>:<16 hex SHA-256>} on the generate path.
     */
    public CompletableFuture<String> requestAsync(
            String prompt,
            UUID playerId,
            GenerationOverrides overrides,
            Duration cacheTtl,
            String knowledgeHash,
            CallTrace trace,
            String admissionKey
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
        String admit = admissionKey == null || admissionKey.isBlank() ? prompt : admissionKey;
        return startShared(key, prompt, admit, playerId, false, true, effective, cacheTtl, trace);
    }

    /**
     * One slot in this client's in-flight map. {@code generate} and {@link #requestAsync} both
     * attach here, so the same cache key shares one provider call for this runtime.
     * A reload builds a new client and therefore a new map.
     */
    @ApiStatus.Internal
    public Flight attach(String cacheKey) {
        Objects.requireNonNull(cacheKey, "cacheKey");
        CompletableFuture<SharedCompletion> created = new CompletableFuture<>();
        CompletableFuture<SharedCompletion> existing = inFlight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return new Flight(false, existing);
        }
        return new Flight(true, created);
    }

    /**
     * Completes the leader's slot once and drops it. A second completion is ignored.
     */
    @ApiStatus.Internal
    public void completeShared(String cacheKey, CompletableFuture<SharedCompletion> owned, SharedCompletion completion) {
        if (owned == null || cacheKey == null) {
            return;
        }
        owned.complete(completion == null
                ? SharedCompletion.fail(new IllegalStateException("Missing completion"), null)
                : completion);
        inFlight.remove(cacheKey, owned);
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
        return generateFreshAsync(prompt, admissionKey, overrides, null);
    }

    /**
     * Pool refills are the only production caller. A null trace is recorded as {@code POOL}.
     */
    public CompletableFuture<String> generateFreshAsync(
            String prompt,
            String admissionKey,
            GenerationOverrides overrides,
            CallTrace trace
    ) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(admissionKey, "admissionKey");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CallTrace call = trace == null
                ? CallTrace.start(io.github.neareststep.nexusai.api.RequestOrigin.POOL, null, "", "")
                : trace;
        if (!config.canSendChatRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(null, admissionKey, false);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        QuotaPolicy.Decision quota = reserve(prompt, effective, call);
        if (quota != null && !quota.allowed()) {
            return quotaRejected(quota.message());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(admissionKey);
        CompletableFuture<SharedCompletion> created = new CompletableFuture<>();
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, created, false, null, effective, null, true, false, call,
                quota == null ? null : quota.hold());
        return adapt(created);
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
        return testAsync(prompt, overrides, null);
    }

    /**
     * {@code /nai test} is the only production caller. A null trace is recorded as {@code TEST}.
     */
    public CompletableFuture<String> testAsync(String prompt, GenerationOverrides overrides, CallTrace trace) {
        Objects.requireNonNull(prompt, "prompt");
        CallTrace call = trace == null
                ? CallTrace.start(io.github.neareststep.nexusai.api.RequestOrigin.TEST, null, "", "")
                : trace;
        if (!config.canSendChatRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Optional<String> rejection = gate.tryAdmit(RateLimiter.SERVER_SENTINEL, prompt, true);
        if (rejection.isPresent()) {
            return rejected(rejection.get());
        }
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        QuotaPolicy.Decision quota = reserve(prompt, effective, call);
        if (quota != null && !quota.allowed()) {
            return quotaRejected(quota.message());
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(prompt);
        CompletableFuture<SharedCompletion> created = new CompletableFuture<>();
        dispatch(prompt, prompt, pauseStamp, failureEpoch, created, false, null, effective, null, false, true, call,
                quota == null ? null : quota.hold());
        return adapt(created);
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

    /**
     * @return true when this provider is inside the current pause. A pause with no provider id covers every provider.
     */
    public boolean isProviderPaused(String providerId) {
        return gate.isProviderPaused(providerId);
    }

    public boolean pauseIsGlobal() {
        return gate.pauseIsGlobal();
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
            Duration cacheTtl,
            CallTrace trace
    ) {
        if (!config.canSendChatRequests()) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI API key is not configured"));
        }
        Flight flight = attach(cacheKey);
        CompletableFuture<String> adapted = adapt(flight.future());
        if (!flight.leader()) {
            return adapted;
        }
        Optional<String> rejection = gate.tryAdmit(playerId, admissionKey, bypassBackoffAndPause);
        if (rejection.isPresent()) {
            completeShared(
                    cacheKey,
                    flight.future(),
                    SharedCompletion.fail(new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejection.get(), null), null));
            return adapted;
        }
        QuotaPolicy.Decision quota = reserve(prompt, overrides, trace);
        if (quota != null && !quota.allowed()) {
            completeShared(
                    cacheKey,
                    flight.future(),
                    SharedCompletion.fail(new AiRequestException(AiErrorKind.LOCAL_QUOTA, 0, quota.message(), null), null));
            return adapted;
        }
        long pauseStamp = gate.pauseStamp();
        long failureEpoch = gate.failureEpoch(admissionKey);
        dispatch(prompt, admissionKey, pauseStamp, failureEpoch, flight.future(), writeCache, cacheKey, overrides, cacheTtl, true, false, trace,
                quota == null ? null : quota.hold());
        return adapted;
    }

    private static CompletableFuture<String> adapt(CompletableFuture<SharedCompletion> shared) {
        CompletableFuture<String> text = new CompletableFuture<>();
        shared.whenComplete((completion, error) -> {
            if (error != null || completion == null || !completion.ok()) {
                Throwable failure = error != null
                        ? error
                        : completion == null || completion.failure() == null
                        ? new IllegalStateException("Missing completion")
                        : completion.failure();
                text.completeExceptionally(AiErrors.unwrap(failure));
                return;
            }
            String value = completion.answer().text();
            text.complete(value == null ? "" : value);
        });
        return text;
    }

    private void dispatch(
            String prompt,
            String admissionKey,
            long pauseStamp,
            long failureEpoch,
            CompletableFuture<SharedCompletion> created,
            boolean writeCache,
            String cacheKey,
            GenerationOverrides overrides,
            Duration cacheTtl,
            boolean clearPause,
            boolean ignoreCooldown,
            CallTrace trace,
            QuotaPolicy.Hold quotaHold
    ) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CompletableFuture<ModelAnswer> upstream;
        try {
            upstream = provider.answer(prompt, effective, ignoreCooldown, trace);
        } catch (RuntimeException e) {
            finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, null, e, writeCache, cacheTtl, clearPause, quotaHold);
            return;
        }
        upstream.whenComplete((answer, error) -> {
            try {
                finish(cacheKey, admissionKey, pauseStamp, failureEpoch, created, answer, error, writeCache,
                        effectiveCacheTtl(cacheTtl, answer), clearPause, quotaHold);
            } catch (Throwable thrown) {
                logger.log(Level.WARNING, "AI completion handler failed", thrown);
                created.completeExceptionally(thrown);
                if (cacheKey != null) {
                    inFlight.remove(cacheKey, created);
                }
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
            CompletableFuture<SharedCompletion> created,
            ModelAnswer answer,
            Throwable error,
            boolean writeCache,
            Duration cacheTtl,
            boolean clearPause,
            QuotaPolicy.Hold quotaHold
    ) {
        String value = answer == null ? null : answer.text();
        try {
            if (error == null && PlayerInput.emptiedByMarkup(value, config.allowMarkup())) {
                error = new AiRequestException(AiErrorKind.MARKUP_ONLY, 0, PlayerInput.MARKUP_ONLY, null);
                value = null;
            } else if (error == null && (value == null || value.isBlank()
                    || PlayerInput.stripSectionSigns(value, config.allowMarkup()).isBlank())) {
                error = new AiRequestException(AiErrorKind.EMPTY_REPLY, 0, PlayerInput.EMPTY_REPLY, null);
                value = null;
            }
            if (error == null && value != null && !value.isBlank()) {
                if (writeCache && cacheKey != null) {
                    String providerId = answer == null ? "" : answer.providerId();
                    String model = answer == null ? "" : answer.model();
                    if (cacheTtl != null) {
                        cache.put(cacheKey, value, providerId, model, cacheTtl);
                    } else {
                        cache.put(cacheKey, value, providerId, model);
                    }
                }
                gate.recordSuccess(admissionKey, pauseStamp, failureEpoch, clearPause);
                created.complete(SharedCompletion.ok(answer));
            } else if (error != null || value == null || value.isBlank()) {
                if (AiErrors.localMissingKey(error)) {
                    created.completeExceptionally(AiErrors.unwrap(error));
                    return;
                }
                Throwable failure = error != null
                        ? error
                        : new AiRequestException(AiErrorKind.OTHER, 0, "OpenAI response missing choices/message/content", null);
                AiErrorKind kind = AiErrors.classify(failure);
                if (kind == AiErrorKind.MARKUP_ONLY) {
                    // clearPause is false for /nai test, so a probe does not start the hold.
                    gate.recordFailure(admissionKey, kind, 0L, clearPause);
                    logger.fine(PlayerInput.MARKUP_ONLY);
                } else if (kind != AiErrorKind.LOCAL_LIMIT && kind != AiErrorKind.LOCAL_QUOTA
                        && kind != AiErrorKind.REJECTED) {
                    AiRequestException typed = AiErrors.find(failure);
                    long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
                    gate.recordFailure(
                            admissionKey, kind, retryAfter, clearPause, AiRequestException.pausedProvidersOf(failure));
                    diagnostics.report(kind, failureDetail(admissionKey, kind, failure), gate.isPaused());
                }
                if (kind == AiErrorKind.REJECTED) {
                    logger.log(Level.FINE, "Rejected model answer: {0}", AiErrors.detail(failure));
                } else {
                    logger.log(Level.FINE, "AI request failed", failure);
                }
                created.complete(SharedCompletion.fail(AiErrors.unwrap(failure), null));
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "AI completion handler failed", e);
            created.completeExceptionally(e);
        } finally {
            if (quotaHold != null) {
                quotaHold.releaseWith(quotas);
            }
            if (cacheKey != null) {
                inFlight.remove(cacheKey, created);
            }
        }
    }

    private QuotaPolicy.Decision reserve(String prompt, GenerationOverrides overrides, CallTrace trace) {
        QuotaPolicy policy = quotas;
        if (policy == null) {
            return null;
        }
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        long estimate = QuotaEstimates.forCall(config, prompt, effective, effective.model(config.getModel()));
        return policy.tryReserve(QuotaPolicy.Charge.of(trace, estimate, null));
    }

    private static CompletableFuture<String> quotaRejected(String reason) {
        return CompletableFuture.failedFuture(new AiRequestException(
                AiErrorKind.LOCAL_QUOTA, 0, reason == null || reason.isBlank() ? "Token quota reached" : reason, null));
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
        if (kind == AiErrorKind.LOCAL_LIMIT || kind == AiErrorKind.LOCAL_QUOTA || kind == AiErrorKind.REJECTED
                || kind == AiErrorKind.MARKUP_ONLY) {
            return;
        }
        AiRequestException typed = AiErrors.find(error);
        long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
        gate.recordFailure(admissionKey, kind, retryAfter, true, AiRequestException.pausedProvidersOf(error));
        diagnostics.report(kind, AiErrors.detail(error), gate.isPaused());
    }

    /**
     * Spends a rate-limit slot without treating a provider pause as a block.
     * {@code /nai talk} uses this when another provider can still answer.
     */
    public Optional<String> tryAdmitIgnoringPause(UUID playerId, String admissionKey) {
        return gate.tryAdmitIgnoringPause(playerId, admissionKey);
    }

    /**
     * Clears per-prompt backoff for this key and leaves the provider pause in place.
     */
    public void recordSuccessKeepingPause(String admissionKey) {
        gate.recordSuccess(admissionKey, gate.pauseStamp(), gate.failureEpoch(admissionKey), false);
    }

    public io.github.neareststep.nexusai.ai.KeyRing sharedRing(String providerId) {
        if (provider instanceof RoutingProvider routing) {
            return routing.sharedRing(providerId);
        }
        return new io.github.neareststep.nexusai.ai.KeyRing(java.util.List.of());
    }

    /**
     * Leader or joiner of one cache key on this client.
     */
    @ApiStatus.Internal
    public record Flight(boolean leader, CompletableFuture<SharedCompletion> future) {
    }
}
