package io.github.neareststep.nexusai.api;

/**
 * Where a generation request started.
 * <p>
 * New values may appear in a later version. A {@code switch} on this enum should keep a
 * {@code default} branch.
 */
public enum RequestOrigin {
    /** {@code NexusAIApi.generate}. */
    API,
    /** {@code %ainexus_cached_*%}. */
    PLACEHOLDER,
    /** A refill of the pool behind {@code %ainexus_generate_*%}. */
    POOL,
    /** Cache prewarm. */
    PREWARM,
    /** {@code /nai test}. */
    TEST,
    /** One {@code /nai talk} reply, or {@code NexusAIApi.talk}. */
    TALK,
    /** A generated greeting. A greeting written in the character profile does not call the model. */
    TALK_GREETING,
    /** A dialogue summary. */
    SUMMARY,
    /** A chat moderation check. */
    MODERATION
}
