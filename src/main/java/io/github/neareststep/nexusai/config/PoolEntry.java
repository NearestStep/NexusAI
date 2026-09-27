package io.github.neareststep.nexusai.config;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * One configured response pool: target size, refill threshold, and delivery vars.
 */
public record PoolEntry(
        String prompt,
        int size,
        int minThreshold,
        Map<String, String> vars,
        GenerationOverrides overrides
) {

    public PoolEntry(String prompt, int size, int minThreshold, Map<String, String> vars) {
        this(prompt, size, minThreshold, vars, GenerationOverrides.none());
    }

    public PoolEntry {
        Objects.requireNonNull(prompt, "prompt");
        vars = vars == null || vars.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(Map.copyOf(vars));
        if (overrides == null) {
            overrides = GenerationOverrides.none();
        }
    }

    public boolean hasVars() {
        return !vars.isEmpty();
    }
}
