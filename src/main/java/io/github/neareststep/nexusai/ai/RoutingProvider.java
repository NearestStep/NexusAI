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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Sends each completion to the first available {@code model-queue} entry and fails over
 * when that entry is rate limited, out of daily budget, or errors.
 */
public final class RoutingProvider implements AiProvider {

    private final PluginConfig config;
    private final ModelQueue queue;
    private final ChatCaller http;
    private final ExecutorService executor;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, KeyRing> rings = new ConcurrentHashMap<>();

    public RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger
    ) {
        this(config, queue, http, executor, logger, System::currentTimeMillis);
    }

    RoutingProvider(
            PluginConfig config,
            ModelQueue queue,
            ChatCaller http,
            ExecutorService executor,
            Logger logger,
            LongSupplier clock
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.http = Objects.requireNonNull(http, "http");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Objects.requireNonNull(prompt, "prompt");
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        return CompletableFuture.supplyAsync(() -> route(prompt, effective, ignoreCooldown), executor);
    }

    /**
     * @param probe {@code /nai test}. Skips temporary cooldown, still honors a daily cap,
     *              and does not mark a failure, skip a key, or lengthen a cooldown.
     */
    private String route(String prompt, GenerationOverrides overrides, boolean probe) {
        long now = clock.getAsLong();
        List<ModelQueue.Choice> choices = queue.selectable(now, probe);
        Attempt last = new Attempt();
        Set<Integer> attempted = new HashSet<>();
        for (ModelQueue.Choice choice : choices) {
            attempted.add(choice.index());
            String model = overrides.modelOverridden() ? overrides.model(choice.model()) : choice.model();
            String answer = tryModel(prompt, overrides, probe, choice.provider(), model, choice.index(), false, last);
            if (answer != null) {
                return answer;
            }
        }
        FallbackModel fallback = overrides.fallbackModel();
        if (fallback != null && fallback.configured()) {
            ModelQueue.FallbackPlan plan = queue.planFallback(
                    fallback.provider(), fallback.model(), clock.getAsLong(), probe, attempted);
            if (plan.allowed()) {
                String answer = tryModel(
                        prompt,
                        overrides,
                        probe,
                        plan.provider(),
                        plan.model(),
                        plan.queueIndex(),
                        plan.dedicated(),
                        last);
                if (answer != null) {
                    return answer;
                }
            }
        }
        if (choices.isEmpty() && (fallback == null || !fallback.configured()) && last.error == null) {
            throw queue.explain(null, clock.getAsLong());
        }
        if (probe && last.error != null) {
            throw last.error;
        }
        throw queue.explain(last.error, clock.getAsLong());
    }

    /**
     * One provider/model attempt, including key rotation. {@code null} means this model did not answer.
     * Replies go through {@link ChatCaller#exchange}, which applies the same reply filter as the queue.
     */
    private String tryModel(
            String prompt,
            GenerationOverrides overrides,
            boolean probe,
            String providerId,
            String model,
            int queueIndex,
            boolean dedicatedFallback,
            Attempt last
    ) {
        ProviderSettings provider = config.provider(providerId);
        if (provider == null) {
            last.error = new AiRequestException(AiErrorKind.OTHER, 0, "Unknown provider " + providerId, null);
            if (!probe) {
                fail(queueIndex, dedicatedFallback, providerId, model, last.error);
            }
            return null;
        }
        KeyRing ring = rings.computeIfAbsent(provider.id(), ignored -> new KeyRing(provider.apiKeys()));
        int attempts = Math.max(1, ring.keys().size());
        for (int attempt = 0; attempt < attempts; attempt++) {
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
                return null;
            }
            if (!provider.hasKeys() && !config.providerAllowsKeyless(provider)) {
                last.error = new AiRequestException(AiErrorKind.BAD_KEY, 0, "API key is not configured", null);
                if (!probe) {
                    fail(queueIndex, dedicatedFallback, providerId, model, last.error);
                }
                return null;
            }
            boolean consumed = dedicatedFallback
                    ? queue.tryConsumeFallback(providerId, model, now)
                    : queue.tryConsume(queueIndex, now);
            if (!consumed) {
                return null;
            }
            try {
                ChatExchange exchange = http.exchange(prompt, overrides, provider.url(), key, model);
                if (!probe) {
                    if (dedicatedFallback) {
                        queue.observeFallback(providerId, model, exchange.headers(), clock.getAsLong());
                    } else {
                        queue.observe(queueIndex, exchange.headers(), clock.getAsLong());
                    }
                }
                return exchange.text();
            } catch (AiRequestException error) {
                last.error = error;
                now = clock.getAsLong();
                if (error.kind() == AiErrorKind.REJECTED) {
                    if (dedicatedFallback) {
                        queue.recordFallbackRejection(providerId, model);
                    } else {
                        queue.recordRejection(queueIndex);
                    }
                    String next = dedicatedFallback
                            ? " Not trying another fallback model."
                            : " Trying the next model-queue entry. No cooldown.";
                    logger.info("Rejected answer from " + providerId + " / " + model
                            + ". " + error.getMessage() + next);
                    return null;
                }
                if (!probe && !key.isEmpty() && (error.kind() == AiErrorKind.BAD_KEY || error.kind() == AiErrorKind.RATE_LIMIT)) {
                    long skipFor = error.kind() == AiErrorKind.BAD_KEY
                            ? config.getAuthPauseSeconds() * 1000L
                            : Math.max(config.getProviderPauseSeconds() * 1000L, error.retryAfterSeconds() * 1000L);
                    ring.skip(key, now + skipFor);
                    String lead = error.kind() == AiErrorKind.BAD_KEY
                            ? "AI provider rejected the API key (invalid or unauthorized)."
                            : "AI provider rate limit.";
                    logger.warning(lead + " Skipping key " + SecretMask.mask(key)
                            + " on " + providerId + " after HTTP " + error.status()
                            + " and trying the next key or model-queue entry.");
                }
                boolean anotherKey = !key.isEmpty() && (probe
                        ? attempt + 1 < attempts
                        : ring.hasAvailable(now));
                if (error.kind() == AiErrorKind.BAD_KEY && anotherKey) {
                    continue;
                }
                if (!probe) {
                    fail(queueIndex, dedicatedFallback, providerId, model, error);
                }
                return null;
            }
        }
        return null;
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
