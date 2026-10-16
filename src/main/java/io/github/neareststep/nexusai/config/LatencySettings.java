package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Perceived-latency controls for {@code /nai talk}. Each part is independent and may be off.
 * Streaming, the thinking indicator, and the reply timeout do not change placeholder or API paths.
 */
public final class LatencySettings {

    private final boolean streamingEnabled;
    private final int charsPerChunk;
    private final int chunkDelayMillis;
    private final boolean thinkingEnabled;
    private final boolean timeoutEnabled;
    private final int timeoutMillis;

    public LatencySettings(
            boolean streamingEnabled,
            int charsPerChunk,
            int chunkDelayMillis,
            boolean thinkingEnabled,
            boolean timeoutEnabled,
            int timeoutMillis
    ) {
        this.streamingEnabled = streamingEnabled;
        this.charsPerChunk = charsPerChunk;
        this.chunkDelayMillis = chunkDelayMillis;
        this.thinkingEnabled = thinkingEnabled;
        this.timeoutEnabled = timeoutEnabled;
        this.timeoutMillis = timeoutMillis;
    }

    public static LatencySettings defaults() {
        return new LatencySettings(false, 48, 40, false, false, 15_000);
    }

    public static LatencySettings read(FileConfiguration config) {
        LatencySettings base = defaults();
        if (config == null) {
            return base;
        }
        return new LatencySettings(
                config.getBoolean("latency.streaming.enabled", base.streamingEnabled),
                clamp(config.getInt("latency.streaming.chars-per-chunk", base.charsPerChunk), 8, 256),
                clamp(config.getInt("latency.streaming.chunk-delay-millis", base.chunkDelayMillis), 0, 2_000),
                config.getBoolean("latency.thinking-indicator.enabled", base.thinkingEnabled),
                config.getBoolean("latency.timeout.enabled", base.timeoutEnabled),
                clamp(config.getInt("latency.timeout.millis", base.timeoutMillis), 100, 300_000)
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean streamingEnabled() {
        return streamingEnabled;
    }

    public int charsPerChunk() {
        return charsPerChunk;
    }

    public int chunkDelayMillis() {
        return chunkDelayMillis;
    }

    public boolean thinkingEnabled() {
        return thinkingEnabled;
    }

    public boolean timeoutEnabled() {
        return timeoutEnabled;
    }

    public int timeoutMillis() {
        return timeoutMillis;
    }
}
