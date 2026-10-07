package io.github.neareststep.nexusai.api;

/**
 * Where {@link GenerationResult#text()} came from.
 * <p>
 * New values may appear in a later version. A {@code switch} on this enum should keep a
 * {@code default} branch.
 */
public enum ResultSource {
    /** This call's model reply, including a reply from {@code fallback-model}. */
    MODEL,
    /** A hit in the shared TTL cache. */
    CACHE,
    /** Joined an equal request that was already in flight. */
    IN_FLIGHT,
    /** The model did not answer. {@link GenerationResult#text()} is the fallback. */
    FALLBACK
}
