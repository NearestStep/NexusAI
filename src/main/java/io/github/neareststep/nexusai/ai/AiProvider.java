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
}
