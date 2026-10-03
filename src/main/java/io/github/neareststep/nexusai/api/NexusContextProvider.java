package io.github.neareststep.nexusai.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Supplies one short line of player state for prompts that list this provider.
 * <p>
 * {@link #provide(ContextRequest)} is called on a {@code nexusai-context-N} thread, never on the
 * main or region thread. Return a future immediately. If the call is still running when its
 * timeout expires, NexusAI interrupts that thread ({@code Future.cancel(true)}). A provider
 * that blocks, including one that sleeps, should stop when the thread is interrupted. The
 * timed-out value is skipped. A {@code null} or blank value means there is nothing to say.
 * <p>
 * A player {@code /nai test} of a prompt that lists {@code context:} is
 * {@link ContextRequest.Purpose#PLACEHOLDER}, the same purpose as a placeholder. There is no
 * separate test purpose.
 * <p>
 * Register through {@link NexusAIApi#registerContextProvider} or
 * {@code ServicesManager.register(NexusContextProvider.class, impl, plugin, ServicePriority.Normal)}.
 * Both paths use the same Bukkit registry. {@code ServicePriority} does not affect order.
 */
public interface NexusContextProvider {

    /** {@code [a-z0-9_]{1,32}}. The prompt lists this id. */
    Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    String id();

    /** Lower values come first. Equal values are ordered by {@link #id()}. */
    default int priority() {
        return 100;
    }

    /**
     * Desired timeout. NexusAI clamps it to {@code context.max-provider-timeout-millis}.
     */
    default Duration timeout() {
        return Duration.ofMillis(100);
    }

    CompletableFuture<String> provide(ContextRequest request);
}
