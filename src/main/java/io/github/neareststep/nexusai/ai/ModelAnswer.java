package io.github.neareststep.nexusai.ai;

import java.time.Duration;

/**
 * One completed reply. {@code cacheTtl} is null when the caller should use the prompt TTL
 * or the cache default. A length-truncated reply leaves it null and uses that normal TTL.
 * <p>
 * Provider, model, usage, and attempt fields are empty when the reply did not come from a model
 * call. The two-argument constructor keeps that empty shape.
 */
public record ModelAnswer(
        String text,
        Duration cacheTtl,
        String providerId,
        String model,
        ResponseUsage usage,
        String finishReason,
        int attempts,
        boolean fallbackModelUsed,
        long httpNanos,
        String structuredMode
) {

    public ModelAnswer(
            String text,
            Duration cacheTtl,
            String providerId,
            String model,
            ResponseUsage usage,
            String finishReason,
            int attempts,
            boolean fallbackModelUsed,
            long httpNanos
    ) {
        this(text, cacheTtl, providerId, model, usage, finishReason, attempts, fallbackModelUsed, httpNanos, "");
    }

    public ModelAnswer(String text, Duration cacheTtl) {
        this(text, cacheTtl, "", "", ResponseUsage.none(), "", 0, false, 0L);
    }

    public static ModelAnswer text(String text) {
        return new ModelAnswer(text, null);
    }

    public ModelAnswer {
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
        structuredMode = structuredMode == null ? "" : structuredMode;
    }
}
