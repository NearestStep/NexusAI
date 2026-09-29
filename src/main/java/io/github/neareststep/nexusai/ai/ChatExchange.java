package io.github.neareststep.nexusai.ai;

import java.util.List;
import java.util.Map;

/**
 * One successful chat completion plus the response headers the queue uses for failover.
 */
public record ChatExchange(String text, Map<String, List<String>> headers) {

    public ChatExchange {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
