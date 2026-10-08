package io.github.neareststep.nexusai.api.event;

import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.TokenUsage;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The model returned text, the filters have run, and the cache and the future have not been written yet.
 * <p>
 * {@link #text()} is the final cleaned reply. It cannot be changed. For {@code /nai talk} this is the
 * line the player will see, after character actions have run. A slow handler blocks a
 * {@code nexusai-http-*} worker.
 */
public final class NexusPostGenerateEvent extends NexusRequestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String text;
    private final String providerId;
    private final String model;
    private final boolean fallbackModelUsed;
    private final TokenUsage usage;
    private final String finishReason;
    private final Duration latency;
    private final int attempts;

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusPostGenerateEvent(
            long requestId,
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String promptId,
            String label,
            String text,
            String providerId,
            String model,
            boolean fallbackModelUsed,
            TokenUsage usage,
            String finishReason,
            Duration latency,
            int attempts
    ) {
        super(requestId, origin, consumer, playerId, promptId, label);
        this.text = text == null ? "" : text;
        this.providerId = providerId == null ? "" : providerId;
        this.model = model == null ? "" : model;
        this.fallbackModelUsed = fallbackModelUsed;
        this.usage = usage == null ? TokenUsage.none() : usage;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.latency = latency == null || latency.isNegative() ? Duration.ZERO : latency;
        this.attempts = Math.max(0, attempts);
    }

    /** Final cleaned text. There is no setter. */
    public String text() {
        return text;
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }

    public boolean fallbackModelUsed() {
        return fallbackModelUsed;
    }

    public TokenUsage usage() {
        return usage;
    }

    public String finishReason() {
        return finishReason;
    }

    /** Time from the start of this request to this event. */
    public Duration latency() {
        return latency;
    }

    /** HTTP attempts for this request, including a retry and a walk of the model queue. */
    public int attempts() {
        return attempts;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    protected boolean sameFields(Object other) {
        NexusPostGenerateEvent that = (NexusPostGenerateEvent) other;
        return fallbackModelUsed == that.fallbackModelUsed
                && attempts == that.attempts
                && text.equals(that.text)
                && providerId.equals(that.providerId)
                && model.equals(that.model)
                && usage.equals(that.usage)
                && finishReason.equals(that.finishReason)
                && latency.equals(that.latency);
    }

    @Override
    protected int fieldsHash() {
        return Objects.hash(text, providerId, model, fallbackModelUsed, usage, finishReason, latency, attempts);
    }

    @Override
    protected String fieldsText() {
        return ", providerId=" + providerId
                + ", model=" + model
                + ", fallbackModelUsed=" + fallbackModelUsed
                + ", attempts=" + attempts
                + ", finishReason=" + finishReason
                + ", latency=" + latency
                + ", usage=" + usage
                + ", text=" + text;
    }
}
