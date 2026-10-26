package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.text.Utf16;

import java.time.Duration;

/**
 * One completed reply. {@code cacheTtl} is null when the caller should use the prompt TTL
 * or the cache default. A length-truncated reply leaves it null and uses that normal TTL.
 * <p>
 * Provider, model, usage, and attempt fields are empty when the reply did not come from a model
 * call. The two-argument constructor keeps that empty shape.
 * <p>
 * A lone UTF-16 surrogate in {@code text} (a provider can send an escaped half of an emoji) is
 * replaced with U+FFFD, so the cache, the answer pool, and the files they are saved to never get
 * one. Text without one is kept as the same instance.
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
        text = Utf16.replaceLoneSurrogates(text);
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
