package io.github.neareststep.nexusai.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One successful chat completion plus the response headers the queue uses for failover.
 * {@code cacheTtl} is an optional per-reply TTL. Null keeps the prompt TTL or the cache default.
 * A reply trimmed because {@code finish_reason} is {@code length} leaves it null.
 * <p>
 * The constructors that only take the text and headers leave provider, model, usage, and
 * attempt fields empty. Routing fills those after the call returns.
 */
public record ChatExchange(
        String text,
        Map<String, List<String>> headers,
        Duration cacheTtl,
        String providerId,
        String model,
        ResponseUsage usage,
        String finishReason,
        int attempts,
        boolean fallbackModelUsed,
        long httpNanos
) {

    public ChatExchange(String text, Map<String, List<String>> headers) {
        this(text, headers, null);
    }

    public ChatExchange(String text, Map<String, List<String>> headers, Duration cacheTtl) {
        this(text, headers, cacheTtl, "", "", ResponseUsage.none(), "", 0, false, 0L);
    }

    public ChatExchange {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        providerId = providerId == null ? "" : providerId;
        model = model == null ? "" : model;
        if (usage == null) {
            usage = ResponseUsage.none();
        }
        finishReason = finishReason == null ? "" : finishReason;
        if (attempts < 0) {
            attempts = 0;
        }
        if (httpNanos < 0) {
            httpNanos = 0;
        }
    }
}
