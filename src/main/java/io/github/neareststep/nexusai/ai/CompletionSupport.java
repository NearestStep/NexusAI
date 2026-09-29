package io.github.neareststep.nexusai.ai;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a completion callback and logs throwable failures.
 * An exception thrown inside {@code whenComplete} is otherwise easy to lose, because nothing joins the future.
 */
public final class CompletionSupport {

    private CompletionSupport() {
    }

    public static <T> void onComplete(
            CompletableFuture<T> future,
            Logger logger,
            String context,
            BiConsumer<T, Throwable> action
    ) {
        future.whenComplete((value, error) -> {
            try {
                action.accept(value, error);
            } catch (Throwable thrown) {
                if (error != null && error != thrown) {
                    thrown.addSuppressed(error);
                }
                logger.log(Level.WARNING, context, thrown);
            }
        });
    }
}
