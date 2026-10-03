package io.github.neareststep.nexusai.config;

/**
 * Where a provider's resolved API keys came from.
 * A literal {@code api-key} is {@link #CONFIG}. {@code ${VAR}}, {@code ${ENV:VAR}}, and
 * {@code NEXUSAI_API_KEY} are {@link #ENV}. {@code api-key-file} is {@link #FILE}.
 */
public enum KeySource {
    CONFIG,
    ENV,
    FILE;

    /**
     * Suffix for status and the startup {@code API keys:} line.
     * A literal config key keeps the 1.0.x mask with no suffix.
     */
    public String statusSuffix() {
        return switch (this) {
            case ENV -> " (env)";
            case FILE -> " (file)";
            case CONFIG -> "";
        };
    }
}
