package io.github.neareststep.nexusai.context;

import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;

/**
 * {@code context.*} from {@code config.yml}. Missing keys use these defaults, so a 1.0.x file
 * behaves as context enabled with the documented limits until {@code ConfigMerger} appends them.
 */
public final class ContextSettings {

    private final boolean enabled;
    private final int maxProviderTimeoutMillis;
    private final int totalTimeoutMillis;
    private final int maxCharsPerProvider;
    private final int maxChars;
    private final int refreshSeconds;
    private final int suspendAfterTimeouts;
    private final int suspendSeconds;
    private final boolean logTimeouts;

    public ContextSettings(
            boolean enabled,
            int maxProviderTimeoutMillis,
            int totalTimeoutMillis,
            int maxCharsPerProvider,
            int maxChars,
            int refreshSeconds,
            int suspendAfterTimeouts,
            int suspendSeconds
    ) {
        this(enabled, maxProviderTimeoutMillis, totalTimeoutMillis, maxCharsPerProvider, maxChars,
                refreshSeconds, suspendAfterTimeouts, suspendSeconds, true);
    }

    public ContextSettings(
            boolean enabled,
            int maxProviderTimeoutMillis,
            int totalTimeoutMillis,
            int maxCharsPerProvider,
            int maxChars,
            int refreshSeconds,
            int suspendAfterTimeouts,
            int suspendSeconds,
            boolean logTimeouts
    ) {
        this.enabled = enabled;
        this.maxProviderTimeoutMillis = maxProviderTimeoutMillis;
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.maxCharsPerProvider = maxCharsPerProvider;
        this.maxChars = maxChars;
        this.refreshSeconds = refreshSeconds;
        this.suspendAfterTimeouts = suspendAfterTimeouts;
        this.suspendSeconds = suspendSeconds;
        this.logTimeouts = logTimeouts;
    }

    public static ContextSettings defaults() {
        return new ContextSettings(true, 200, 300, 200, 600, 30, 5, 60, true);
    }

    public static ContextSettings read(FileConfiguration config) {
        if (config == null) {
            return defaults();
        }
        return new ContextSettings(
                config.getBoolean("context.enabled", true),
                clamp(config.getInt("context.max-provider-timeout-millis", 200), 10, 1000),
                clamp(config.getInt("context.total-timeout-millis", 300), 10, 2000),
                clamp(config.getInt("context.max-chars-per-provider", 200), 20, 1000),
                clamp(config.getInt("context.max-chars", 600), 50, 4000),
                clamp(config.getInt("context.refresh-seconds", 30), 1, 3600),
                clamp(config.getInt("context.suspend-after-timeouts", 5), 1, 100),
                clamp(config.getInt("context.suspend-seconds", 60), 1, 3600),
                config.getBoolean("context.log-timeouts", true)
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public int maxProviderTimeoutMillis() {
        return maxProviderTimeoutMillis;
    }

    public int totalTimeoutMillis() {
        return totalTimeoutMillis;
    }

    public int maxCharsPerProvider() {
        return maxCharsPerProvider;
    }

    public int maxChars() {
        return maxChars;
    }

    public int refreshSeconds() {
        return refreshSeconds;
    }

    public Duration refresh() {
        return Duration.ofSeconds(refreshSeconds);
    }

    public int suspendAfterTimeouts() {
        return suspendAfterTimeouts;
    }

    public int suspendSeconds() {
        return suspendSeconds;
    }

    /** {@code context.log-timeouts}: one INFO line per provider timeout, at most once per error-log cooldown. */
    public boolean logTimeouts() {
        return logTimeouts;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
