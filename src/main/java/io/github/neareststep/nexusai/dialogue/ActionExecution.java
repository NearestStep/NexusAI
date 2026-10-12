package io.github.neareststep.nexusai.dialogue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs one character action after the wait flag is checked.
 * A task that starts after the 5 second wait does not fire the action event and does not run the command.
 */
public final class ActionExecution {

    public static final String BLOCKED = "refused: blocked by server";

    private ActionExecution() {
    }

    /**
     * @param expired set when {@code DialogueService} stops waiting
     * @param cancelled fires the action event and reports whether it was cancelled
     * @param command the command, called only when the wait has not expired and the event was not cancelled
     * @return {@code null} when the wait already expired, {@link #BLOCKED} when the event was cancelled,
     *         otherwise the command result
     */
    public static String execute(AtomicBoolean expired, BooleanSupplier cancelled, Supplier<String> command) {
        if (expired != null && expired.get()) {
            return null;
        }
        boolean blocked = cancelled != null && cancelled.getAsBoolean();
        if (expired != null && expired.get()) {
            return null;
        }
        if (blocked) {
            return BLOCKED;
        }
        if (expired != null && expired.get()) {
            return null;
        }
        return command == null ? null : command.get();
    }
}
