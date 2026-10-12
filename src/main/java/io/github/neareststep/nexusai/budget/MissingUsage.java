package io.github.neareststep.nexusai.budget;

/**
 * What to do when a provider omits {@code usage}.
 * {@link #ESTIMATE} counts {@code ceil(characters / 4)} and marks those tokens estimated.
 * {@link #IGNORE} counts the request and stores zero tokens.
 */
public enum MissingUsage {
    ESTIMATE,
    IGNORE;

    /**
     * {@code estimate} and {@code ignore}. Anything else is empty, and the caller keeps the default.
     */
    public static MissingUsage parse(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "estimate" -> ESTIMATE;
            case "ignore" -> IGNORE;
            default -> null;
        };
    }
}
