package io.github.neareststep.nexusai.api;

/**
 * How {@link NexusAIApi#generate} uses the shared TTL cache.
 * <p>
 * New values may appear in a later version. A {@code switch} on this enum should keep a
 * {@code default} branch.
 */
public enum CacheMode {
    /**
     * Read and write the shared TTL cache, and join an equal request that is already in flight.
     * This is the default.
     */
    CACHED,
    /**
     * Always call the model. The cache is not read and not written, and an in-flight request
     * is not joined.
     */
    FRESH
}
