package io.github.neareststep.nexusai.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One successful chat completion plus the response headers the queue uses for failover.
 * {@code cacheTtl} is set when this reply should not use the normal cache TTL.
 * Null keeps the prompt TTL or the cache default.
 */
public record ChatExchange(String text, Map<String, List<String>> headers, Duration cacheTtl) {

    public ChatExchange(String text, Map<String, List<String>> headers) {
        this(text, headers, null);
    }

    public ChatExchange {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
