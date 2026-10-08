package io.github.neareststep.nexusai.api;

/**
 * Why {@link GenerationResult#success()} is false.
 * <p>
 * New values may appear in a later version. A {@code switch} on this enum should keep a
 * {@code default} branch.
 */
public enum NexusErrorKind {
    /** The provider returned HTTP 429. */
    RATE_LIMIT,
    /** The provider returned HTTP 402 or reported that the account balance is gone. */
    PROVIDER_QUOTA,
    /** The provider returned HTTP 401 or 403. */
    BAD_KEY,
    /** The provider does not know the requested model. */
    UNKNOWN_MODEL,
    /** The HTTP call timed out. */
    TIMEOUT,
    /** HTTP 5xx, a connection failure, HTML instead of JSON, or a response with no content. */
    PROVIDER_ERROR,
    /** A provider pause is in effect. No HTTP call was made. */
    PAUSED,
    /** This prompt is backing off after a provider error. No HTTP call was made. */
    BACKOFF,
    /** {@code limits.requests-per-*} or {@code limits.player-requests-per-*} refused the call. */
    LOCAL_LIMIT,
    /**
     * A NexusAI token or request quota refused the call, or every model-queue row is at its
     * daily limit. No HTTP call was made for a quota refusal.
     */
    QUOTA_EXCEEDED,
    /** The HTTP worker queue is full. */
    QUEUE_FULL,
    /** The reply was dropped by the player-input filter. */
    REJECTED,
    /** The reply was empty after colour codes were removed. */
    EMPTY_REPLY,
    /** The reply was empty only after markup was removed. */
    MARKUP_ONLY,
    /**
     * {@code generateJson} could not extract JSON or the JSON failed the schema.
     * Nothing in this version produces this kind yet.
     */
    INVALID_JSON,
    /** A pre-generate hook cancelled the call. The rate-limit slot is not returned. */
    CANCELLED,
    /** No API key, requests are held after a config error, or {@code plugin-api.enabled} is false. */
    NOT_CONFIGURED,
    /** {@code promptId} is not in {@code prompts.yml}. */
    UNKNOWN_PROMPT,
    /** The template or a variable is longer than {@code plugin-api.*}. */
    INVALID_REQUEST,
    /** The player left before their state could be read. */
    PLAYER_UNAVAILABLE,
    /** NexusAI is shutting down and the call did not finish. */
    SHUTDOWN
}
