package io.github.neareststep.nexusai.api.event;

import io.github.neareststep.nexusai.api.RequestOrigin;
import org.bukkit.event.Event;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A generation that is not a moderation check.
 * <p>
 * Fired asynchronously on a {@code nexusai-http-*} thread or on an {@code HttpClient} thread,
 * never on the region thread. A listener that blocks holds that worker and delays every request
 * that shares it. Read world state from a region scheduler, not from the handler.
 * <p>
 * One request fires {@link NexusPreGenerateEvent}, then zero or more {@link NexusProviderErrorEvent},
 * then exactly one of {@link NexusPostGenerateEvent} or {@link NexusGenerateFailEvent}.
 * A cache hit, an in-flight join, and a pooled answer do not fire these events.
 * Moderation does not fire them either. {@code RequestOrigin} may gain values later;
 * a {@code switch} on {@link #origin()} should keep a {@code default} branch.
 */
public abstract class NexusRequestEvent extends Event {

    private final long requestId;
    private final RequestOrigin origin;
    private final String consumer;
    private final UUID playerId;
    private final String promptId;
    private final String label;

    protected NexusRequestEvent(
            long requestId,
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String promptId,
            String label
    ) {
        super(true);
        this.requestId = requestId;
        this.origin = origin == null ? RequestOrigin.API : origin;
        this.consumer = consumer == null || consumer.isBlank() ? "nexusai" : consumer;
        this.playerId = playerId;
        this.promptId = promptId == null ? "" : promptId;
        this.label = label == null ? "" : label;
    }

    public long requestId() {
        return requestId;
    }

    public RequestOrigin origin() {
        return origin;
    }

    /** {@code nexusai}, or the API plugin's {@code Plugin.getName()}. */
    public String consumer() {
        return consumer;
    }

    public Optional<UUID> playerId() {
        return Optional.ofNullable(playerId);
    }

    /** Prompt id, or {@code ""} for an inline template and for dialogue that has no prompt id. */
    public String promptId() {
        return promptId;
    }

    /** API label, or {@code ""}. */
    public String label() {
        return label;
    }

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other.getClass() != getClass()) {
            return false;
        }
        return sameRequest((NexusRequestEvent) other) && sameFields(other);
    }

    protected final boolean sameRequest(NexusRequestEvent that) {
        return requestId == that.requestId
                && origin == that.origin
                && consumer.equals(that.consumer)
                && Objects.equals(playerId, that.playerId)
                && promptId.equals(that.promptId)
                && label.equals(that.label);
    }

    /** Subclass fields. The request identity is already compared. */
    protected abstract boolean sameFields(Object other);

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), requestId, origin, consumer, playerId, promptId, label, fieldsHash());
    }

    protected abstract int fieldsHash();

    @Override
    public final String toString() {
        return getClass().getSimpleName()
                + "{requestId=" + requestId
                + ", origin=" + origin
                + ", consumer=" + consumer
                + ", playerId=" + (playerId == null ? "" : playerId)
                + ", promptId=" + promptId
                + ", label=" + label
                + fieldsText()
                + "}";
    }

    protected abstract String fieldsText();
}
