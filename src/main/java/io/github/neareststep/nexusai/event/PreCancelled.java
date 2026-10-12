package io.github.neareststep.nexusai.event;

import java.util.concurrent.CompletionException;

/**
 * A listener cancelled {@link io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent}.
 * No HTTP is sent. The failure is not a provider error: it does not pause, back off, or cool a row.
 */
public final class PreCancelled extends RuntimeException {

    private final String reason;

    public PreCancelled(String reason) {
        super(reason == null || reason.isBlank() ? "Cancelled" : reason);
        this.reason = getMessage();
    }

    public String reason() {
        return reason;
    }

    public static PreCancelled find(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof PreCancelled cancelled) {
                return cancelled;
            }
            if (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            current = current.getCause();
        }
        return null;
    }
}
