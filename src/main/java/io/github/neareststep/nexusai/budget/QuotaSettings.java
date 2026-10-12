package io.github.neareststep.nexusai.budget;

import java.util.Map;

/**
 * Daily quota limits from {@code config.yml}. {@code 0} means no limit.
 * Limits apply only when {@link #enabled()} is true. Accounting does not read this.
 */
public final class QuotaSettings {

    private static final QuotaSettings OFF = new QuotaSettings(false, 0L, 0L, Map.of(), Map.of());

    private final boolean enabled;
    private final long serverTokensPerDay;
    private final long playerTokensPerDay;
    private final Map<String, GroupLimit> groups;
    private final Map<String, ConsumerLimit> consumers;

    public QuotaSettings(
            boolean enabled,
            long serverTokensPerDay,
            long playerTokensPerDay,
            Map<String, GroupLimit> groups,
            Map<String, ConsumerLimit> consumers
    ) {
        this.enabled = enabled;
        this.serverTokensPerDay = Math.max(0L, serverTokensPerDay);
        this.playerTokensPerDay = Math.max(0L, playerTokensPerDay);
        this.groups = groups == null || groups.isEmpty() ? Map.of() : Map.copyOf(groups);
        this.consumers = consumers == null || consumers.isEmpty() ? Map.of() : Map.copyOf(consumers);
    }

    public static QuotaSettings off() {
        return OFF;
    }

    public boolean enabled() {
        return enabled;
    }

    public long serverTokensPerDay() {
        return serverTokensPerDay;
    }

    public long playerTokensPerDay() {
        return playerTokensPerDay;
    }

    public Map<String, GroupLimit> groups() {
        return groups;
    }

    public boolean hasGroups() {
        return !groups.isEmpty();
    }

    public Map<String, ConsumerLimit> consumers() {
        return consumers;
    }

    /**
     * Limit for one API plugin. A plugin without its own entry uses {@code default},
     * and that default is not a shared pot: each plugin is counted on its own.
     */
    public ConsumerLimit consumer(String pluginName) {
        if (pluginName != null) {
            ConsumerLimit specific = consumers.get(pluginName);
            if (specific != null) {
                return specific;
            }
        }
        ConsumerLimit fallback = consumers.get("default");
        return fallback == null ? ConsumerLimit.NONE : fallback;
    }

    public record GroupLimit(long tokensPerDay, long requestsPerDay) {
        public GroupLimit {
            tokensPerDay = Math.max(0L, tokensPerDay);
            requestsPerDay = Math.max(0L, requestsPerDay);
        }
    }

    public record ConsumerLimit(long tokensPerDay, long requestsPerDay) {
        public static final ConsumerLimit NONE = new ConsumerLimit(0L, 0L);

        public ConsumerLimit {
            tokensPerDay = Math.max(0L, tokensPerDay);
            requestsPerDay = Math.max(0L, requestsPerDay);
        }
    }
}
