package io.github.neareststep.nexusai.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * One configured response pool: target size, refill threshold, and delivery vars.
 */
public record PoolEntry(String prompt, int size, int minThreshold, Map<String, String> vars) {

    public PoolEntry {
        Objects.requireNonNull(prompt, "prompt");
        vars = vars == null || vars.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(vars));
    }

    public boolean hasVars() {
        return !vars.isEmpty();
    }
}
