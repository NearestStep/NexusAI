package io.github.neareststep.nexusai.ai;

import java.util.concurrent.CompletableFuture;

/**
 * Pluggable AI backend.
 */
public interface AiProvider {

    /**
     * Completes the prompt asynchronously. Must never block the Bukkit main thread.
     */
    CompletableFuture<String> complete(String prompt);
}
