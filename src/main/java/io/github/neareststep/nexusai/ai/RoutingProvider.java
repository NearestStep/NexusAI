package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;

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
        return tryModel(prompt, overrides, probe, choice.provider(), model, choice.index(), false, last, 0, trace)
                .thenCompose(answer -> {
                    if (answer != null) {
                        return CompletableFuture.completedFuture(
                                toAnswer(answer, choice.provider(), model, last.httpAttempts, false));
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
                        trace
                ).thenCompose(answer -> {
                    if (answer != null) {
                        return CompletableFuture.completedFuture(
                                toAnswer(answer, plan.provider(), plan.model(), last.httpAttempts, true));
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
        return gate.schedule(() -> {
            long calledAt = clock.getAsLong();
            boolean consumed = dedicatedFallback
                    ? queue.tryConsumeFallback(providerId, model, calledAt)
                    : queue.tryConsume(queueIndex, calledAt);
            if (!consumed) {
                return CompletableFuture.completedFuture(null);
            }
            last.httpAttempts++;
            return http.exchangeAsync(prompt, overrides, provider.url(), apiKey, model, trace);
        }).handle((ChatExchange exchange, Throwable error) -> afterCall(
                prompt,
                overrides,
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
                trace
        )).thenCompose(next -> next);
    }

    private static ModelAnswer toAnswer(
            ChatExchange exchange,
            String providerId,
            String model,
            int attempts,
            boolean fallbackModelUsed
    ) {
        return new ModelAnswer(
                exchange.text(),
                exchange.cacheTtl(),
                providerId,
                model,
                exchange.usage(),
                exchange.finishReason(),
                attempts,
                fallbackModelUsed,
                exchange.httpNanos());
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
            CallTrace trace
    ) {
        if (error == null) {
            if (exchange == null) {
                return CompletableFuture.completedFuture(null);
            }
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
            return tryModel(
                    prompt, overrides, probe, providerId, model, queueIndex, dedicatedFallback, last, keyAttempt + 1, trace);
        }
        if (!probe) {
            fail(queueIndex, dedicatedFallback, providerId, model, typed);
        }
        return CompletableFuture.completedFuture(null);
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
