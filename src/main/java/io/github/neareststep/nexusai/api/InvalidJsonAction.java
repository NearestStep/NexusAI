package io.github.neareststep.nexusai.api;

/**
 * What {@link NexusAIApi#generateJson} does when a model reply fails local schema checks.
 */
public enum InvalidJsonAction {

    /** Send one or more correction retries, then fall back if still invalid. */
    RETRY,

    /** Skip correction retries and complete with {@link NexusErrorKind#INVALID_JSON} and the fallback text. */
    FALLBACK
}
