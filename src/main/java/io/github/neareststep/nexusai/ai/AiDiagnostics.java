package io.github.neareststep.nexusai.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Remembers the latest provider failure and logs each kind at WARNING with a cooldown.
 */
public final class AiDiagnostics {

    private final Logger logger;
    private final long cooldownMillis;
    private final LongSupplier clock;
    private final Object logGate = new Object();
    private final ConcurrentHashMap<AiErrorKind, Long> lastLoggedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AiErrorKind, String> lastLoggedText = new ConcurrentHashMap<>();
    private volatile String lastError;

    public AiDiagnostics(Logger logger, Duration cooldown) {
        this(logger, cooldown, System::currentTimeMillis);
    }

    AiDiagnostics(Logger logger, Duration cooldown, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.cooldownMillis = Math.max(1L, cooldown == null ? 30_000L : cooldown.toMillis());
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void report(AiErrorKind kind, String detail) {
        report(kind, detail, false);
    }

    /**
     * @param paused {@code true} only when this failure actually paused requests to the provider
     */
    public void report(AiErrorKind kind, String detail, boolean paused) {
        if (kind == null || kind == AiErrorKind.LOCAL_LIMIT || kind == AiErrorKind.REJECTED
                || kind == AiErrorKind.MARKUP_ONLY) {
            return;
        }
        String message = format(kind, detail, paused);
        // One pool refill finishes on several threads at once. The cooldown and the
        // logged text have to be decided together, or each thread writes the same line.
        synchronized (logGate) {
            lastError = message;
            long now = clock.getAsLong();
            Long previous = lastLoggedAt.get(kind);
            boolean cooled = previous != null && now - previous < cooldownMillis;
            // An empty-reply line names a retry time. Write it again when that time changes
            // so the warning matches Last error. Other kinds keep the first line for the cooldown.
            if (cooled && (kind != AiErrorKind.EMPTY_REPLY || message.equals(lastLoggedText.get(kind)))) {
                return;
            }
            lastLoggedAt.put(kind, now);
            lastLoggedText.put(kind, message);
            logger.warning(message);
        }
    }

    public String lastError() {
        return lastError;
    }

    static String format(AiErrorKind kind, String detail, boolean paused) {
        String lead = switch (kind) {
            case RATE_LIMIT -> "AI provider rate limit.";
            case QUOTA -> "AI provider quota or billing limit.";
            case BAD_KEY -> "AI provider rejected the API key (invalid or unauthorized).";
            case UNKNOWN_MODEL -> "AI provider does not recognize the configured model.";
            case TIMEOUT -> "AI provider request timed out.";
            case LOCAL_LIMIT -> "Local rate limit reached.";
            case REJECTED -> "AI answer rejected.";
            case EMPTY_REPLY -> PlayerInput.EMPTY_REPLY;
            case MARKUP_ONLY -> PlayerInput.MARKUP_ONLY;
            case OTHER -> "AI provider request failed.";
        };
        if (paused && kind.pausesProvider()) {
            lead = lead + " Requests to this provider are paused.";
        }
        if (detail == null || detail.isBlank()) {
            return lead;
        }
        String trimmed = detail.length() > 300 ? detail.substring(0, 300) + "..." : detail;
        return lead + " " + trimmed;
    }
}
