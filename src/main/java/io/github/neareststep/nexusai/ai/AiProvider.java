package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable AI backend.
 */
public interface AiProvider {

    /**
     * Completes the prompt asynchronously. Must never block the Bukkit main thread.
     */
    CompletableFuture<String> complete(String prompt);

    /**
     * Same as {@link #complete(String)} with optional per-call overrides.
     * {@code null} overrides mean the provider defaults.
     */
    default CompletableFuture<String> complete(String prompt, GenerationOverrides overrides) {
        return complete(prompt);
    }

    /**
     * Same as {@link #complete(String, GenerationOverrides)}.
     * When {@code ignoreCooldown} is true, a probe may call a model that is in a temporary
     * error or rate-limit cooldown. Daily caps still apply. Implementations that have no
     * cooldown ignore the flag.
     */
    default CompletableFuture<String> complete(String prompt, GenerationOverrides overrides, boolean ignoreCooldown) {
        return complete(prompt, overrides);
    }

    /**
     * Same call as {@link #complete(String, GenerationOverrides, boolean)}.
     * The default drops any per-reply cache TTL. {@link RoutingProvider} keeps one when the
     * reply was cut off by {@code finish_reason=length}.
     */
    default CompletableFuture<ModelAnswer> answer(String prompt, GenerationOverrides overrides, boolean ignoreCooldown) {
        return complete(prompt, overrides, ignoreCooldown).thenApply(ModelAnswer::text);
    }
}
