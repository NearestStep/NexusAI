package io.github.neareststep.nexusai.config;

/**
 * One configured response pool: target size and refill threshold.
 */
public record PoolEntry(String prompt, int size, int minThreshold) {
}
