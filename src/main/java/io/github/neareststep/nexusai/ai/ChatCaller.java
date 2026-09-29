package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.GenerationOverrides;

/**
 * Synchronous chat call used by {@link RoutingProvider}. {@link OpenAiProvider} is the HTTP implementation.
 */
public interface ChatCaller {

    ChatExchange exchange(
            String prompt,
            GenerationOverrides overrides,
            String baseUrl,
            String apiKey,
            String model
    );
}
