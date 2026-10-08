package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.budget.QuotaPolicy;
import io.github.neareststep.nexusai.budget.TokenAccounting;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;
import io.github.neareststep.nexusai.event.EventDispatcher;
import io.github.neareststep.nexusai.event.GenerationEvents;
import io.github.neareststep.nexusai.event.PreCancelled;
import io.github.neareststep.nexusai.json.StructuredOutputSupport;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Sends each completion through {@code model-queue}. Failover starts at the first available row.
 * Round-robin starts at the next row. A failure keeps walking the circle the same way.
 * The HTTP call is asynchronous: this class does not block a worker on the response.
 */
public final class RoutingProvider implements AiProvider {

    private final PluginConfig config;
    private final ModelQueue queue;
    private final ChatCaller http;
    private final ExecutorService executor;
    private final Logger logger;
    private final LongSupplier clock;
    private final HttpGate gate;
    private final Map<String, KeyRing> rings = new ConcurrentHashMap<>();
    private volatile TokenAccounting accounting = TokenAccounting.none();
    private volatile QuotaPolicy quotas;

    public RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger
    ) {
        this(config, queue, http, executor, logger, System::currentTimeMillis, HttpGate.unlimited());
    }

    public RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger,
            HttpGate gate
    ) {
        this(config, queue, http, executor, logger, System::currentTimeMillis, gate);
    }

    RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger,
            LongSupplier clock
    ) {
        this(config, queue, http, executor, logger, clock, HttpGate.unlimited());
    }

    RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger,
            LongSupplier clock,
            HttpGate gate
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.http = Objects.requireNonNull(http, "http");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.gate = gate == null ? HttpGate.unlimited() : gate;
    }

    /** Counts HTTP attempts. Unset until the plugin attaches the ledger; tests may leave it unset. */
    /** Row token caps. Server, player, and consumer caps are reserved at the entrance, not here. */
    public void quotas(QuotaPolicy policy) {
        this.quotas = policy;
    }

    public void tokenAccounting(TokenAccounting accounting) {
        this.accounting = accounting == null ? TokenAccounting.none() : accounting;
    }

    @Override
    public CompletableFuture<String> complete(String prompt) {
        return complete(prompt, GenerationOverrides.none());
    }

    @Override
    public CompletableFuture<String> complete(String prompt, GenerationOverrides overrides) {
        return complete(prompt, overrides, false);
    }

    @Override
    public CompletableFuture<String> complete(String prompt, GenerationOverrides overrides, boolean ignoreCooldown) {
        return answer(prompt, overrides, ignoreCooldown).thenApply(ModelAnswer::text);
    }

    @Override
    public CompletableFuture<ModelAnswer> answer(String prompt, GenerationOverrides overrides, boolean ignoreCooldown) {
        return answer(prompt, overrides, ignoreCooldown, null);
    }

    @Override
    public CompletableFuture<ModelAnswer> answer(
            String prompt,
            GenerationOverrides overrides,
            boolean ignoreCooldown,
            CallTrace trace
    ) {
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CompletableFuture<ModelAnswer> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    route(prompt, effective, ignoreCooldown, trace).whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(value);
                        }
                    });
                } catch (Throwable thrown) {
                    result.completeExceptionally(thrown);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(HttpPool.queueFull(rejected));
        }
        return result;
    }

    /**
     * @param probe {@code /nai test}. Skips temporary cooldown, still honors a daily cap,
     *              and does not mark a failure, skip a key, or lengthen a cooldown.
     */
    private CompletableFuture<ModelAnswer> route(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            CallTrace trace
    ) {
        long now = clock.getAsLong();
        List<ModelQueue.Choice> choices = queue.selectable(now, probe);
        Attempt last = new Attempt();
        Set<Integer> attempted = new HashSet<>();
        PreCancelled cancelled = openPre(prompt, overrides, choices, trace);
        if (cancelled != null) {
            return CompletableFuture.failedFuture(cancelled);
        }
        return walk(prompt, overrides, probe, choices, 0, attempted, last, trace);
    }

    private CompletableFuture<ModelAnswer> walk(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            List<ModelQueue.Choice> choices,
            int index,
            Set<Integer> attempted,
            Attempt last,
            CallTrace trace
    ) {
        if (index >= choices.size()) {
            return walkFallback(prompt, overrides, probe, choices, attempted, last, trace);
        }
        ModelQueue.Choice choice = choices.get(index);
        attempted.add(choice.index());
        String model = overrides.modelOverridden() ? overrides.model(choice.model()) : choice.model();
        boolean moreAfter = index + 1 < choices.size() || fallbackConfigured(overrides);
        return tryModel(prompt, overrides, probe, choice.provider(), model, choice.index(), false, last, 0, moreAfter, trace)
                .thenCompose(answer -> {
                    if (answer != null) {
                        return CompletableFuture.completedFuture(
                                toAnswer(answer, choice.provider(), model, last.httpAttempts, false, overrides));
                    }
                    return walk(prompt, overrides, probe, choices, index + 1, attempted, last, trace);
                });
    }

    private CompletableFuture<ModelAnswer> walkFallback(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            List<ModelQueue.Choice> choices,
            Set<Integer> attempted,
            Attempt last,
            CallTrace trace
    ) {
        FallbackModel fallback = overrides.fallbackModel();
        if (fallback != null && fallback.configured()) {
            ModelQueue.FallbackPlan plan = queue.planFallback(
                    fallback.provider(), fallback.model(), clock.getAsLong(), probe, attempted);
            if (plan.allowed()) {
                return tryModel(
                        prompt,
                        overrides,
                        probe,
                        plan.provider(),
                        plan.model(),
                        plan.queueIndex(),
                        plan.dedicated(),
                        last,
                        0,
                        false,
                        trace
                ).thenCompose(answer -> {
                    if (answer != null) {
                        return CompletableFuture.completedFuture(
                                toAnswer(answer, plan.provider(), plan.model(), last.httpAttempts, true, overrides));
                    }
                    return failed(probe, choices, fallback, last);
                });
            }
        }
        return failed(probe, choices, fallback, last);
    }

    private CompletableFuture<ModelAnswer> failed(
            boolean probe,
            List<ModelQueue.Choice> choices,
            FallbackModel fallback,
            Attempt last
    ) {
        if (choices.isEmpty() && (fallback == null || !fallback.configured()) && last.error == null) {
            return CompletableFuture.failedFuture(queue.explain(null, clock.getAsLong()));
        }
        if (probe && last.error != null) {
            return CompletableFuture.failedFuture(last.error);
        }
        return CompletableFuture.failedFuture(queue.explain(last.error, clock.getAsLong()));
    }

    /**
     * One provider/model attempt, including key rotation. {@code null} means this model did not answer.
     * Replies go through {@link ChatCaller#exchange}, which applies the same reply filter as the queue.
     */
    private CompletableFuture<ChatExchange> tryModel(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            String providerId,
            String model,
            int queueIndex,
            boolean dedicatedFallback,
            Attempt last,
            int keyAttempt,
            boolean moreAfter,
            CallTrace trace
    ) {
        ProviderSettings provider = config.provider(providerId);
        if (provider == null) {
            last.error = new AiRequestException(AiErrorKind.OTHER, 0, "Unknown provider " + providerId, null);
            if (!probe) {
                fail(queueIndex, dedicatedFallback, providerId, model, last.error);
            }
            return CompletableFuture.completedFuture(null);
        }
        KeyRing ring = rings.computeIfAbsent(provider.id(), ignored -> new KeyRing(provider.apiKeys()));
        int attempts = Math.max(1, ring.keys().size());
        if (keyAttempt >= attempts) {
            return CompletableFuture.completedFuture(null);
        }
        long now = clock.getAsLong();
        String key = ring.acquire(now, probe);
        if (key == null) {
            if (!probe) {
                long until = Math.max(now + 1_000L, ring.nextReadyAt(now));
                if (dedicatedFallback) {
                    queue.cooldownFallback(providerId, model, until, ModelQueue.Hold.ERROR);
                } else {
                    queue.cooldown(queueIndex, until, ModelQueue.Hold.ERROR);
                }
            }
            return CompletableFuture.completedFuture(null);
        }
        if (!provider.hasKeys() && !config.providerAllowsKeyless(provider)) {
            // No HTTP call. Do not record this as a rejected key or cool the row down.
            last.error = new AiRequestException(AiErrorKind.OTHER, 0, "API key is not configured", null);
            return CompletableFuture.completedFuture(null);
        }
        String apiKey = key;
        GenerationOverrides call = StructuredOutputSupport.prepare(
                overrides, providerId, model, provider.structuredOutput());
        StructuredOutputSupport.noteRow(call, queueIndex, dedicatedFallback);
        QuotaPolicy.Decision rowDecision = reserveRow(prompt, call, providerId, model, queueIndex, dedicatedFallback);
        if (rowDecision != null && !rowDecision.allowed()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return gate.schedule(() -> {
                long calledAt = clock.getAsLong();
                boolean consumed = dedicatedFallback
                        ? queue.tryConsumeFallback(providerId, model, calledAt)
                        : queue.tryConsume(queueIndex, calledAt);
                if (!consumed) {
                    return CompletableFuture.completedFuture(null);
                }
                last.httpAttempts++;
                StructuredOutputSupport.noteAttempt(call);
                return http.exchangeAsync(prompt, call, provider.url(), apiKey, model, trace);
            }).handle((ChatExchange exchange, Throwable error) -> afterCall(
                    prompt,
                    call,
                    probe,
                    providerId,
                    model,
                    queueIndex,
                    dedicatedFallback,
                    last,
                    keyAttempt,
                    attempts,
                    ring,
                    apiKey,
                    exchange,
                    error,
                    trace,
                    rowDecision,
                    moreAfter
            )).thenCompose(next -> next);
        } catch (RuntimeException ex) {
            releaseRow(rowDecision);
            throw ex;
        }
    }

    /**
     * Reserves the row token cap before the request slot is taken. A dedicated fallback that is not
     * a queue row has no {@code daily-token-limit}. A denied row is skipped and is not cooled down.
     */
    private QuotaPolicy.Decision reserveRow(
            String prompt,
            GenerationOverrides overrides,
            String providerId,
            String model,
            int queueIndex,
            boolean dedicatedFallback
    ) {
        QuotaPolicy policy = quotas;
        if (policy == null) {
            return null;
        }
        long limit = dedicatedFallback ? 0L : queue.dailyTokenLimit(queueIndex);
        String storageId = dedicatedFallback
                ? ModelQueue.fallbackStorageId(providerId, model)
                : queue.rowStorageId(queueIndex);
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        long estimate = QuotaEstimates.forCall(config, prompt, effective, model);
        return policy.tryReserveRow(storageId, limit, estimate);
    }

    private void releaseRow(QuotaPolicy.Decision decision) {
        if (decision != null && quotas != null) {
            decision.hold().releaseWith(quotas);
        }
    }

    private static ModelAnswer toAnswer(
            ChatExchange exchange,
            String providerId,
            String model,
            int attempts,
            boolean fallbackModelUsed,
            GenerationOverrides overrides
    ) {
        String mode = StructuredOutputSupport.isJson(overrides)
                ? StructuredOutputSupport.active(overrides).name()
                : "";
        return new ModelAnswer(
                exchange.text(),
                exchange.cacheTtl(),
                providerId,
                model,
                exchange.usage(),
                exchange.finishReason(),
                attempts,
                fallbackModelUsed,
                exchange.httpNanos(),
                mode);
    }

    private CompletableFuture<ChatExchange> afterCall(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            String providerId,
            String model,
            int queueIndex,
            boolean dedicatedFallback,
            Attempt last,
            int keyAttempt,
            int attempts,
            KeyRing ring,
            String key,
            ChatExchange exchange,
            Throwable error,
            CallTrace trace,
            QuotaPolicy.Decision rowDecision,
            boolean moreAfter
    ) {
        try {
        if (PreCancelled.find(error) != null) {
            return CompletableFuture.failedFuture(PreCancelled.find(error));
        }
        if (error == null) {
            if (exchange == null) {
                return CompletableFuture.completedFuture(null);
            }
            accounting.record(exchange.usage(), trace, providerId, queueIndex, dedicatedFallback, queue, model);
            if (!probe) {
                if (dedicatedFallback) {
                    queue.observeFallback(providerId, model, exchange.headers(), clock.getAsLong());
                } else {
                    queue.observe(queueIndex, exchange.headers(), clock.getAsLong());
                }
            }
            return CompletableFuture.completedFuture(exchange);
        }
        if (HttpPool.isQueueFull(error)) {
            return CompletableFuture.failedFuture(HttpPool.queueFull(error));
        }
        AiRequestException typed = asAi(error);
        accounting.record(typed.usage(), trace, providerId, queueIndex, dedicatedFallback, queue, model);
        if (typed.kind().pausesProvider()) {
            last.pausedProviders.add(providerId);
            for (String paused : last.pausedProviders) {
                typed = typed.withPausedProvider(paused);
            }
        }
        last.error = typed;
        long now = clock.getAsLong();
        if (typed.kind() == AiErrorKind.EMPTY_REPLY || typed.kind() == AiErrorKind.MARKUP_ONLY) {
            return CompletableFuture.failedFuture(typed);
        }
        if (typed.kind() == AiErrorKind.REJECTED) {
            if (dedicatedFallback) {
                queue.recordFallbackRejection(providerId, model);
            } else {
                queue.recordRejection(queueIndex);
            }
            String next = dedicatedFallback
                    ? " Not trying another fallback model."
                    : " Trying the next model-queue entry. No cooldown.";
            logger.info("Rejected answer from " + providerId + " / " + model
                    + ". " + SecretMask.redact(typed.getMessage(), config.configuredSecrets()) + next);
            return CompletableFuture.completedFuture(null);
        }
        if (!probe && !key.isEmpty() && (typed.kind() == AiErrorKind.BAD_KEY || typed.kind() == AiErrorKind.RATE_LIMIT)) {
            long skipFor = typed.kind() == AiErrorKind.BAD_KEY
                    ? config.getAuthPauseSeconds() * 1000L
                    : Math.max(config.getProviderPauseSeconds() * 1000L, typed.retryAfterSeconds() * 1000L);
            ring.skip(key, now + skipFor);
            String lead = typed.kind() == AiErrorKind.BAD_KEY
                    ? "AI provider rejected the API key (invalid or unauthorized)."
                    : "AI provider rate limit.";
            logger.warning(lead + " Skipping key " + SecretMask.mask(key)
                    + " on " + providerId + " after HTTP " + typed.status()
                    + " and trying the next key or model-queue entry.");
        }
        boolean anotherKey = !key.isEmpty() && (probe
                ? keyAttempt + 1 < attempts
                : ring.hasAvailable(now));
        if (typed.kind() == AiErrorKind.BAD_KEY && anotherKey) {
            noteProviderError(trace, providerId, model, typed, true);
            return tryModel(
                    prompt, overrides, probe, providerId, model, queueIndex, dedicatedFallback, last, keyAttempt + 1, moreAfter, trace);
        }
        if (StructuredOutputSupport.downgrade(overrides, providerId, model, typed, logger)) {
            noteProviderError(trace, providerId, model, typed, true);
            releaseRow(rowDecision);
            rowDecision = null;
            return tryModel(
                    prompt, overrides, probe, providerId, model, queueIndex, dedicatedFallback, last, 0, moreAfter, trace);
        }
        noteProviderError(trace, providerId, model, typed, moreAfter);
        if (!probe) {
            fail(queueIndex, dedicatedFallback, providerId, model, typed);
        }
        return CompletableFuture.completedFuture(null);
        } finally {
            releaseRow(rowDecision);
        }
    }

    /**
     * One more HTTP call on the same provider and model. Does not fire Pre again.
     * Used for the single JSON repair. A provider error on this call is not a second repair.
     */
    public CompletableFuture<ModelAnswer> repeat(
            String prompt,
            GenerationOverrides overrides,
            String providerId,
            String model,
            CallTrace trace
    ) {
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        CompletableFuture<ModelAnswer> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                Attempt last = new Attempt();
                int noted = StructuredOutputSupport.rowIndex(effective);
                boolean notedDedicated = StructuredOutputSupport.dedicatedRow(effective);
                int index = noted;
                boolean dedicated = notedDedicated;
                if (noted < 0 && !notedDedicated) {
                    index = queue.indexOf(providerId, model);
                    dedicated = index < 0;
                }
                final boolean sameRowDedicated = dedicated;
                final int sameRowIndex = Math.max(index, 0);
                tryModel(
                        prompt,
                        effective,
                        false,
                        providerId,
                        model,
                        sameRowIndex,
                        sameRowDedicated,
                        last,
                        0,
                        false,
                        trace
                ).whenComplete((exchange, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    if (exchange == null) {
                        Throwable cause = last.error != null
                                ? last.error
                                : new AiRequestException(AiErrorKind.OTHER, 0, "JSON repair was not sent", null);
                        result.completeExceptionally(cause);
                        return;
                    }
                    result.complete(toAnswer(exchange, providerId, model, last.httpAttempts, sameRowDedicated, effective));
                });
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(HttpPool.queueFull(rejected));
        }
        return result;
    }

    private static AiRequestException asAi(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof AiRequestException typed) {
            return typed;
        }
        String message = current == null || current.getMessage() == null
                ? "AI request failed"
                : current.getMessage();
        return new AiRequestException(AiErrors.classify(current), 0, message, current);
    }

    private PreCancelled openPre(
            String prompt,
            GenerationOverrides overrides,
            List<ModelQueue.Choice> choices,
            CallTrace trace
    ) {
        if (trace == null || trace.origin() == io.github.neareststep.nexusai.api.RequestOrigin.MODERATION) {
            return null;
        }
        String providerId;
        String model;
        if (choices != null && !choices.isEmpty()) {
            ModelQueue.Choice choice = choices.get(0);
            providerId = choice.provider();
            model = overrides.modelOverridden() ? overrides.model(choice.model()) : choice.model();
        } else if (fallbackConfigured(overrides)) {
            FallbackModel fallback = overrides.fallbackModel();
            if (fallback == null || !fallback.configured()) {
                fallback = config.fallbackModel();
            }
            providerId = fallback.provider();
            model = fallback.model();
        } else {
            return null;
        }
        String system = overrides.systemPrompt(config.getSystemPrompt());
        int estimate = GenerationEvents.estimateTokens((system == null ? "" : system) + (prompt == null ? "" : prompt));
        EventDispatcher.PreOutcome outcome = EventDispatcher.get().pre(trace, providerId, model, estimate);
        if (!outcome.cancelled()) {
            return null;
        }
        return new PreCancelled(outcome.reason());
    }

    private boolean fallbackConfigured(GenerationOverrides overrides) {
        FallbackModel fromCall = overrides == null ? null : overrides.fallbackModel();
        if (fromCall != null && fromCall.configured()) {
            return true;
        }
        FallbackModel global = config.fallbackModel();
        return global != null && global.configured();
    }

    private static void noteProviderError(CallTrace trace, String providerId, String model, Throwable error, boolean willRetry) {
        if (trace == null || error == null || PreCancelled.find(error) != null) {
            return;
        }
        AiRequestException typed = error instanceof AiRequestException ai ? ai : asAi(error);
        if (GenerationEvents.providerKind(typed.kind()) == null) {
            return;
        }
        EventDispatcher.get().providerError(trace, providerId, model, typed, willRetry);
    }

    private void fail(int queueIndex, boolean dedicatedFallback, String providerId, String model, AiRequestException error) {
        long now = clock.getAsLong();
        if (dedicatedFallback) {
            queue.markFallbackFailure(providerId, model, error, now);
        } else {
            queue.markFailure(queueIndex, error, now);
        }
    }

    private static final class Attempt {
        private AiRequestException error;
        private int httpAttempts;
        private final java.util.LinkedHashSet<String> pausedProviders = new java.util.LinkedHashSet<>();
    }

    /**
     * Key ring shared with dialogue calls so a 401 or 429 skip applies to both paths.
     */
    public KeyRing sharedRing(String providerId) {
        ProviderSettings provider = config.provider(providerId);
        List<String> keys = provider == null ? List.of() : provider.apiKeys();
        return rings.computeIfAbsent(providerId, ignored -> new KeyRing(keys));
    }
}
