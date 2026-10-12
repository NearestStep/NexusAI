package io.github.neareststep.nexusai.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired asynchronously after a flagged chat line is appended to the moderation log and before
 * staff are notified. The flag cannot be cancelled. The log line is already written.
 * A slow handler blocks a {@code nexusai-http-*} worker.
 * <p>
 * {@link #message()} is the chat line that was logged. {@link #reason()} is at most 240 characters,
 * the same clip the verdict parser uses.
 */
public final class NexusModerationFlagEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String message;
    private final String category;
    private final String reason;

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusModerationFlagEvent(UUID playerId, String playerName, String message, String category, String reason) {
        super(true);
        this.playerId = playerId == null ? new UUID(0L, 0L) : playerId;
        this.playerName = playerName == null ? "" : playerName;
        this.message = message == null ? "" : message;
        this.category = category == null ? "" : category;
        this.reason = reason == null ? "" : reason;
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    /** Chat text, the same line that was written to the moderation log. */
    public String message() {
        return message;
    }

    /** {@code toxicity}, {@code insult}, {@code veiled insult}, {@code harassment}, or {@code spam}. */
    public String category() {
        return category;
    }

    /** At most 240 characters. */
    public String reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NexusModerationFlagEvent that)) {
            return false;
        }
        return playerId.equals(that.playerId)
                && playerName.equals(that.playerName)
                && message.equals(that.message)
                && category.equals(that.category)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, playerName, message, category, reason);
    }

    @Override
    public String toString() {
        return "NexusModerationFlagEvent{playerId=" + playerId
                + ", playerName=" + playerName
                + ", category=" + category
                + ", reason=" + reason
                + ", message=" + message
                + "}";
    }
}
