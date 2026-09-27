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
    private final ConcurrentHashMap<AiErrorKind, Long> lastLoggedAt = new ConcurrentHashMap<>();
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
        if (kind == null || kind == AiErrorKind.LOCAL_LIMIT) {
            return;
        }
        String message = format(kind, detail);
        lastError = message;
        long now = clock.getAsLong();
        Long previous = lastLoggedAt.get(kind);
        if (previous != null && now - previous < cooldownMillis) {
            return;
        }
        lastLoggedAt.put(kind, now);
        logger.warning(message);
    }

    public String lastError() {
        return lastError;
    }

    static String format(AiErrorKind kind, String detail) {
        String lead = switch (kind) {
            case RATE_LIMIT -> "AI provider rate limit. Requests to this provider are paused.";
            case QUOTA -> "AI provider quota or billing limit. Requests to this provider are paused.";
            case BAD_KEY -> "AI provider rejected the API key. Requests to this provider are paused.";
            case UNKNOWN_MODEL -> "AI provider does not recognize the configured model.";
            case TIMEOUT -> "AI provider request timed out.";
            case LOCAL_LIMIT -> "Local rate limit reached.";
            case OTHER -> "AI provider request failed.";
        };
        if (detail == null || detail.isBlank()) {
            return lead;
        }
        String trimmed = detail.length() > 300 ? detail.substring(0, 300) + "..." : detail;
        return lead + " " + trimmed;
    }
}
