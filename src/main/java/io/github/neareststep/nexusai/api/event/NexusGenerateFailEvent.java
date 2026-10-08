package io.github.neareststep.nexusai.api.event;

import io.github.neareststep.nexusai.api.GenerationError;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * A request that passed {@link NexusPreGenerateEvent} ended without model text,
 * or an API call failed for any reason.
 * <p>
 * A cancelled pre-generate event arrives here as {@link NexusErrorKind#CANCELLED}.
 * Placeholder, talk, greeting, pool, prewarm, test, and summary admission refusals do not
 * fire this event. Fail without Pre is only for an API call. A slow handler
 * blocks a {@code nexusai-http-*} worker. While the server is stopping, shutdown does not
 * fire this event.
 */
public final class NexusGenerateFailEvent extends NexusRequestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GenerationError error;
    private final int attempts;
    private final Duration latency;

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusGenerateFailEvent(
            long requestId,
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String promptId,
            String label,
            GenerationError error,
            int attempts,
            Duration latency
    ) {
        super(requestId, origin, consumer, playerId, promptId, label);
        this.error = error == null
                ? GenerationError.of(NexusErrorKind.PROVIDER_ERROR, "", 0, 0L)
                : error;
        this.attempts = Math.max(0, attempts);
        this.latency = latency == null || latency.isNegative() ? Duration.ZERO : latency;
    }

    public GenerationError error() {
        return error;
    }

    public int attempts() {
        return attempts;
    }

    /** Time from the start of this request to this event. */
    public Duration latency() {
        return latency;
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
        NexusGenerateFailEvent that = (NexusGenerateFailEvent) other;
        return attempts == that.attempts && error.equals(that.error) && latency.equals(that.latency);
    }

    @Override
    protected int fieldsHash() {
        return Objects.hash(error, attempts, latency);
    }

    @Override
    protected String fieldsText() {
        return ", attempts=" + attempts + ", latency=" + latency + ", error=" + error;
    }
}
