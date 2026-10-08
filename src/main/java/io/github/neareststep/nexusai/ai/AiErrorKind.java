package io.github.neareststep.nexusai.ai;

/**
 * Classified outcome of an AI call, used for logs, pause decisions, and {@code /nai status}.
 */
public enum AiErrorKind {
    RATE_LIMIT("rate-limit"),
    QUOTA("quota"),
    BAD_KEY("bad-key"),
    UNKNOWN_MODEL("unknown-model"),
    TIMEOUT("timeout"),
    OTHER("other"),
    /** Local minute/day limiter. Not a provider failure. */
    LOCAL_LIMIT("local-limit"),
    /**
     * A NexusAI daily quota refused the call before HTTP. Not a provider failure,
     * so it does not pause a provider, start backoff, or cool a queue row.
     * This is not {@link #QUOTA}, which is an HTTP 402 from the provider.
     */
    LOCAL_QUOTA("local-quota"),
    /** The model leaked a player-input boundary or restated the guard. Not a provider failure. */
    REJECTED("rejected"),
    /**
     * The reply was empty after colour codes were removed. Backs that prompt off
     * for 5, then 15, then 30 minutes, capped at 60. Not a provider outage and not a guard rejection.
     */
    EMPTY_REPLY("empty-reply"),
    /**
     * The reply was empty only after hex, MiniMessage, or an interactive JSON component
     * was removed. Not cached. Placeholders and the pool wait {@code 30s} before asking
     * again and serve fallback during that hold. The hold does not climb and is not the
     * empty-reply pause. {@code /nai test} stays immediate. A colour-only reply is still
     * {@link #EMPTY_REPLY}.
     */
    MARKUP_ONLY("markup-only");

    private final String langKey;

    AiErrorKind(String langKey) {
        this.langKey = langKey;
    }

    public String langKey() {
        return langKey;
    }

    /**
     * Auth, quota, and provider rate-limit failures pause every request to that provider.
     */
    public boolean pausesProvider() {
        return this == RATE_LIMIT || this == QUOTA || this == BAD_KEY;
    }
}
