package io.github.neareststep.nexusai.api.event;

import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * One failed HTTP attempt: HTTP 4xx or 5xx, a timeout, or a connection error.
 * <p>
 * Fired for every origin, including moderation. A walk of the model queue, a key rotation,
 * or {@code fallback-model} sets {@link #willRetry()} and does not start a new pre-generate event.
 * {@link #message()} is masked and at most 300 Unicode code points. It is not the raw provider body,
 * and it has no URL query string. A slow handler blocks a {@code nexusai-http-*} worker.
 * {@link NexusErrorKind} may gain values later; keep a {@code default} branch.
 */
public final class NexusProviderErrorEvent extends NexusRequestEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String providerId;
    private final String model;
    private final NexusErrorKind kind;
    private final int httpStatus;
    private final String message;
    private final boolean willRetry;

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusProviderErrorEvent(
            long requestId,
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String promptId,
            String label,
            String providerId,
            String model,
            NexusErrorKind kind,
            int httpStatus,
            String message,
            boolean willRetry
    ) {
        super(requestId, origin, consumer, playerId, promptId, label);
        this.providerId = providerId == null ? "" : providerId;
        this.model = model == null ? "" : model;
        this.kind = kind == null ? NexusErrorKind.PROVIDER_ERROR : kind;
        this.httpStatus = Math.max(0, httpStatus);
        this.message = message == null ? "" : message;
        this.willRetry = willRetry;
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }

    /**
     * {@link NexusErrorKind#RATE_LIMIT}, {@link NexusErrorKind#PROVIDER_QUOTA},
     * {@link NexusErrorKind#BAD_KEY}, {@link NexusErrorKind#UNKNOWN_MODEL},
     * {@link NexusErrorKind#TIMEOUT}, or {@link NexusErrorKind#PROVIDER_ERROR}.
     */
    public NexusErrorKind kind() {
        return kind;
    }

    /** HTTP status, or {@code 0} when the call did not return one. */
    public int httpStatus() {
        return httpStatus;
    }

    /** Masked, at most 300 Unicode code points. */
    public String message() {
        return message;
    }

    /** Routing will try another row, another key, or {@code fallback-model}. */
    public boolean willRetry() {
        return willRetry;
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
        NexusProviderErrorEvent that = (NexusProviderErrorEvent) other;
        return httpStatus == that.httpStatus
                && willRetry == that.willRetry
                && providerId.equals(that.providerId)
                && model.equals(that.model)
                && kind == that.kind
                && message.equals(that.message);
    }

    @Override
    protected int fieldsHash() {
        return Objects.hash(providerId, model, kind, httpStatus, message, willRetry);
    }

    @Override
    protected String fieldsText() {
        return ", providerId=" + providerId
                + ", model=" + model
                + ", kind=" + kind
                + ", httpStatus=" + httpStatus
                + ", willRetry=" + willRetry
                + ", message=" + message;
    }
}
