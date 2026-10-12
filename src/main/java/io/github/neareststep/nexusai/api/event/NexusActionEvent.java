package io.github.neareststep.nexusai.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Fired on the thread that is about to run a character action, before the command.
 * <p>
 * {@code as: console} runs on the global region thread. {@code as: player} runs on the player's
 * region thread. On Paper both are the main thread, so a handler may read the region, the world,
 * and the player's permissions directly. Cancelling skips the command. The model is told
 * {@code refused: blocked by server}. The action cooldown and the daily counter are not spent.
 * {@link #command()} is the command after {@code {player}} and {@code {uuid}} are filled in,
 * without a leading slash. It cannot be changed.
 */
public final class NexusActionEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String characterId;
    private final String actionName;
    private final String command;
    private final boolean console;
    private final long requestId;
    private boolean cancelled;

    /** Not part of the plugin API. Callers do not construct this event. */
    @ApiStatus.Internal
    public NexusActionEvent(
            Player player,
            String characterId,
            String actionName,
            String command,
            boolean console,
            long requestId
    ) {
        super(false);
        this.player = player;
        this.characterId = characterId == null ? "" : characterId;
        this.actionName = actionName == null ? "" : actionName;
        this.command = command == null ? "" : command;
        this.console = console;
        this.requestId = requestId;
    }

    public Player player() {
        return player;
    }

    public String characterId() {
        return characterId;
    }

    public String actionName() {
        return actionName;
    }

    /** Command after substitution, without a leading slash. There is no setter. */
    public String command() {
        return command;
    }

    /** {@code true} when the action runs as the console. */
    public boolean console() {
        return console;
    }

    /** The talk turn this action belongs to. */
    public long requestId() {
        return requestId;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private static String playerLabel(Player player) {
        if (player == null) {
            return "";
        }
        try {
            String name = player.getName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NexusActionEvent that)) {
            return false;
        }
        return console == that.console
                && requestId == that.requestId
                && cancelled == that.cancelled
                && Objects.equals(player, that.player)
                && characterId.equals(that.characterId)
                && actionName.equals(that.actionName)
                && command.equals(that.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(player, characterId, actionName, command, console, requestId, cancelled);
    }

    @Override
    public String toString() {
        return "NexusActionEvent{player=" + playerLabel(player)
                + ", characterId=" + characterId
                + ", actionName=" + actionName
                + ", command=" + command
                + ", console=" + console
                + ", requestId=" + requestId
                + ", cancelled=" + cancelled
                + "}";
    }
}
