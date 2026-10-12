package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.ai.CallTrace;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * One finished {@link NexusAIApi#generate} call.
 * <p>
 * {@link #text()} is the cleaned model reply, or the fallback chain when {@link #success()}
 * is false: the request fallback, then the prompt fallback, then {@code fallback} from
 * {@code config.yml}. Fields do not contain API keys, HTTP headers, or the provider body.
 */
public final class GenerationResult {

    private final long requestId;
    private final boolean success;
    private final String text;
    private final ResultSource source;
    private final String promptId;
    private final String providerId;
    private final String model;
    private final boolean fallbackModelUsed;
    private final TokenUsage usage;
    private final String finishReason;
    private final boolean truncated;
    private final int attempts;
    private final Duration latency;
    private final Duration modelLatency;
    private final GenerationError error;
    private final String label;

    private GenerationResult(
            long requestId,
            boolean success,
            String text,
            ResultSource source,
            String promptId,
            String providerId,
            String model,
            boolean fallbackModelUsed,
            TokenUsage usage,
            String finishReason,
            boolean truncated,
            int attempts,
            Duration latency,
            Duration modelLatency,
            GenerationError error,
            String label
    ) {
        this.requestId = requestId;
        this.success = success;
        this.text = text == null ? "" : text;
        this.source = source == null ? ResultSource.FALLBACK : source;
        this.promptId = promptId == null ? "" : promptId;
        this.providerId = providerId == null ? "" : providerId;
        this.model = model == null ? "" : model;
        this.fallbackModelUsed = fallbackModelUsed;
        this.usage = usage == null ? TokenUsage.none() : usage;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.truncated = truncated;
        this.attempts = Math.max(0, attempts);
        this.latency = latency == null || latency.isNegative() ? Duration.ZERO : latency;
        this.modelLatency = modelLatency == null || modelLatency.isNegative() ? Duration.ZERO : modelLatency;
        this.error = success ? null : error;
        this.label = label == null ? "" : label;
    }

    /**
     * Builds a result and measures latency from {@code trace} to now.
     * Not part of the plugin API.
     */
    @ApiStatus.Internal
    public static GenerationResult of(
            CallTrace trace,
            boolean success,
            String text,
            ResultSource source,
            String providerId,
            String model,
            boolean fallbackModelUsed,
            TokenUsage usage,
            String finishReason,
            boolean truncated,
            int attempts,
            Duration modelLatency,
            GenerationError error
    ) {
        long started = trace == null ? System.nanoTime() : trace.startedNanos();
        Duration latency = Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
        return new GenerationResult(
                trace == null ? 0L : trace.requestId(),
                success,
                text,
                source,
                trace == null ? "" : trace.promptId(),
                providerId,
                model,
                fallbackModelUsed,
                usage,
                finishReason,
                truncated,
                attempts,
                latency,
                modelLatency,
                error,
                trace == null ? "" : trace.label());
    }

    public long requestId() {
        return requestId;
    }

    public boolean success() {
        return success;
    }

    /**
     * Cleaned model text, or the fallback chain when {@link #success()} is false.
     */
    public String text() {
        return text;
    }

    /** The model text when {@link #success()} is true, otherwise empty. */
    public Optional<String> textIfSuccess() {
        return success ? Optional.of(text) : Optional.empty();
    }

    public ResultSource source() {
        return source;
    }

    /** Prompt id, or {@code ""} for an inline template. */
    public String promptId() {
        return promptId;
    }

    /** Provider id, or {@code ""} when it is not known. */
    public String providerId() {
        return providerId;
    }

    /** Model id, or {@code ""} when it is not known. */
    public String model() {
        return model;
    }

    public boolean fallbackModelUsed() {
        return fallbackModelUsed;
    }

    public TokenUsage usage() {
        return usage;
    }

    /** Provider {@code finish_reason}, such as {@code stop} or {@code length}, or {@code ""}. */
    public String finishReason() {
        return finishReason;
    }

    /** True when {@code finish_reason} is {@code length}. */
    public boolean truncated() {
        return truncated;
    }

    /** HTTP attempts for this call. {@code 0} for a cache hit or an in-flight join. */
    public int attempts() {
        return attempts;
    }

    /** Time from the {@code generate} call to this result. */
    public Duration latency() {
        return latency;
    }

    /** HTTP time of the successful attempt, or {@link Duration#ZERO}. */
    public Duration modelLatency() {
        return modelLatency;
    }

    public Optional<GenerationError> error() {
        return error == null ? Optional.empty() : Optional.of(error);
    }

    /** Correlation label, or {@code ""} when the request set none. */
    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenerationResult that)) {
            return false;
        }
        return requestId == that.requestId
                && success == that.success
                && fallbackModelUsed == that.fallbackModelUsed
                && truncated == that.truncated
                && attempts == that.attempts
                && text.equals(that.text)
                && source == that.source
                && promptId.equals(that.promptId)
                && providerId.equals(that.providerId)
                && model.equals(that.model)
                && usage.equals(that.usage)
                && finishReason.equals(that.finishReason)
                && latency.equals(that.latency)
                && modelLatency.equals(that.modelLatency)
                && Objects.equals(error, that.error)
                && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requestId, success, text, source, promptId, providerId, model, fallbackModelUsed,
                usage, finishReason, truncated, attempts, latency, modelLatency, error, label);
    }

    @Override
    public String toString() {
        String shown = text.length() > 120 ? text.substring(0, text.offsetByCodePoints(0, Math.min(120, text.codePointCount(0, text.length())))) : text;
        return "GenerationResult{requestId=" + requestId
                + ", success=" + success
                + ", source=" + source
                + ", promptId=" + promptId
                + ", providerId=" + providerId
                + ", model=" + model
                + ", finishReason=" + finishReason
                + ", attempts=" + attempts
                + ", label=" + label
                + ", error=" + (error == null ? "" : error.kind())
                + ", text=" + shown
                + "}";
    }
}
