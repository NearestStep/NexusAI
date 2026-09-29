package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (choices.isEmpty()) {
            throw queue.explain(null, now);
        }
        AiRequestException last = null;
        for (ModelQueue.Choice choice : choices) {
            ProviderSettings provider = config.provider(choice.provider());
            if (provider == null) {
                last = new AiRequestException(AiErrorKind.OTHER, 0, "Unknown provider " + choice.provider(), null);
                if (!probe) {
                    queue.markFailure(choice.index(), last, clock.getAsLong());
                }
                continue;
            }
            String model = overrides.modelOverridden() ? overrides.model(choice.model()) : choice.model();
            KeyRing ring = rings.computeIfAbsent(provider.id(), ignored -> new KeyRing(provider.apiKeys()));
            int attempts = Math.max(1, ring.keys().size());
            for (int attempt = 0; attempt < attempts; attempt++) {
                now = clock.getAsLong();
                String key = ring.acquire(now, probe);
                if (key == null) {
                    if (!probe) {
                        queue.cooldown(choice.index(), Math.max(now + 1_000L, ring.nextReadyAt(now)), ModelQueue.Hold.ERROR);
                    }
                    break;
                }
                if (!provider.hasKeys() && !config.providerAllowsKeyless(provider)) {
                    last = new AiRequestException(AiErrorKind.BAD_KEY, 0, "API key is not configured", null);
                    if (!probe) {
                        queue.markFailure(choice.index(), last, now);
                    }
                    break;
                }
                if (!queue.tryConsume(choice.index(), now)) {
                    break;
                }
                try {
                    ChatExchange exchange = http.exchange(prompt, overrides, provider.url(), key, model);
                    if (!probe) {
                        queue.observe(choice.index(), exchange.headers(), clock.getAsLong());
                    }
                    return exchange.text();
                } catch (AiRequestException error) {
                    last = error;
                    now = clock.getAsLong();
                    if (!probe && !key.isEmpty() && (error.kind() == AiErrorKind.BAD_KEY || error.kind() == AiErrorKind.RATE_LIMIT)) {
                        long skipFor = error.kind() == AiErrorKind.BAD_KEY
                                ? config.getAuthPauseSeconds() * 1000L
                                : Math.max(config.getProviderPauseSeconds() * 1000L, error.retryAfterSeconds() * 1000L);
                        ring.skip(key, now + skipFor);
                        String lead = error.kind() == AiErrorKind.BAD_KEY
                                ? "AI provider rejected the API key (invalid or unauthorized)."
                                : "AI provider rate limit.";
                        logger.warning(lead + " Skipping key " + SecretMask.mask(key)
                                + " on " + choice.provider() + " after HTTP " + error.status()
                                + " and trying the next key or model-queue entry.");
                    }
                    boolean anotherKey = !key.isEmpty() && (probe
                            ? attempt + 1 < attempts
                            : ring.hasAvailable(now));
                    if (error.kind() == AiErrorKind.BAD_KEY && anotherKey) {
                        continue;
                    }
                    if (!probe) {
                        queue.markFailure(choice.index(), error, now);
                    }
                    break;
                }
            }
        }
        if (probe && last != null) {
            throw last;
        }
        throw queue.explain(last, clock.getAsLong());
    }
}
