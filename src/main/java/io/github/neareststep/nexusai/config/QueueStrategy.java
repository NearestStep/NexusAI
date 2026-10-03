package io.github.neareststep.nexusai.config;

import java.util.Locale;

/**
 * How a new request picks its first {@code model-queue} row.
 * {@link #FAILOVER} is the 1.0.x order: the first available row, then the next only after a failure.
 * {@link #ROUND_ROBIN} starts each new request at the next configured row and then walks the circle.
 */
public enum QueueStrategy {
    FAILOVER("failover"),
    ROUND_ROBIN("round-robin");

    private final String wire;

    QueueStrategy(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /**
     * @return the strategy, or {@code null} when {@code raw} is non-blank and not a known name
     */
    public static QueueStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAILOVER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (QueueStrategy strategy : values()) {
            if (strategy.wire.equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }
}
