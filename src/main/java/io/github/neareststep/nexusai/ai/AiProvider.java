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
     * The default drops any per-reply cache TTL. A reply cut off by {@code finish_reason=length}
     * does not set one; the caller caches it for the prompt TTL or {@code cache.ttl}.
     */
    default CompletableFuture<ModelAnswer> answer(String prompt, GenerationOverrides overrides, boolean ignoreCooldown) {
        return complete(prompt, overrides, ignoreCooldown).thenApply(ModelAnswer::text);
    }

    /**
     * Same call as {@link #answer(String, GenerationOverrides, boolean)}.
     * {@code trace} rides along for metadata. Providers that do not override this method
     * keep their three-argument {@code answer} and drop the trace.
     */
    default CompletableFuture<ModelAnswer> answer(
            String prompt,
            GenerationOverrides overrides,
            boolean ignoreCooldown,
            CallTrace trace
    ) {
        return answer(prompt, overrides, ignoreCooldown);
    }
}
