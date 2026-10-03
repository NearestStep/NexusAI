package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.util.concurrent.CompletableFuture;

/**
 * Chat call used by {@link RoutingProvider}. {@link OpenAiProvider} is the HTTP implementation.
 * {@link #exchangeAsync} must not block the caller. The default runs {@link #exchange} and
 * completes immediately, which is what tests do.
 */
public interface ChatCaller {

    ChatExchange exchange(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    );

    default CompletableFuture<ChatExchange> exchangeAsync(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    ) {
        try {
            return CompletableFuture.completedFuture(exchange(prompt, overrides, baseUrl, apiKey, model));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
