package io.github.neareststep.nexusai.ai;

/**
 * Failure of a single completion, with a status code when the provider returned one.
 */
public final class AiRequestException extends RuntimeException {

    private final AiErrorKind kind;
    private final int status;
    private final long retryAfterSeconds;
    private final java.util.Map<String, java.util.List<String>> headers;
    private final boolean unsupportedTools;
    private final java.util.Set<String> pausedProviders;
    private final ResponseUsage usage;

    public AiRequestException(AiErrorKind kind, int status, String message, Throwable cause) {
        this(kind, status, message, cause, 0L);
    }

    public AiRequestException(AiErrorKind kind, int status, String message, Throwable cause, long retryAfterSeconds) {
        this(kind, status, message, cause, retryAfterSeconds, java.util.Map.of());
    }

    public AiRequestException(
            AiErrorKind kind,
            int status,
            String message,
            Throwable cause,
            long retryAfterSeconds,
            java.util.Map<String, java.util.List<String>> headers
    ) {
        this(kind, status, message, cause, retryAfterSeconds, headers, false);
    }

    public AiRequestException(
            AiErrorKind kind,
            int status,
            String message,
            Throwable cause,
            long retryAfterSeconds,
            java.util.Map<String, java.util.List<String>> headers,
            boolean unsupportedTools
    ) {
        this(kind, status, message, cause, retryAfterSeconds, headers, unsupportedTools, java.util.Set.of(), ResponseUsage.none());
    }

    private AiRequestException(
            AiErrorKind kind,
            int status,
            String message,
            Throwable cause,
            long retryAfterSeconds,
            java.util.Map<String, java.util.List<String>> headers,
            boolean unsupportedTools,
            java.util.Set<String> pausedProviders,
            ResponseUsage usage
    ) {
        super(message, cause);
        this.kind = kind == null ? AiErrorKind.OTHER : kind;
        this.status = status;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        this.headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        this.unsupportedTools = unsupportedTools;
        this.pausedProviders = pausedProviders == null || pausedProviders.isEmpty()
                ? java.util.Set.of()
                : java.util.Set.copyOf(pausedProviders);
        this.usage = usage == null ? ResponseUsage.none() : usage;
    }

    /**
     * Usage parsed from a body NexusAI then discarded. Error statuses without a usage object stay
     * {@link ResponseUsage#none()}.
     */
    public ResponseUsage usage() {
        return usage;
    }

    /** A copy that carries {@code usage}. The same instance is returned when nothing changes. */
    public AiRequestException withUsage(ResponseUsage usage) {
        ResponseUsage next = usage == null ? ResponseUsage.none() : usage;
        if (next.equals(this.usage)) {
            return this;
        }
        return new AiRequestException(
                kind, status, getMessage(), getCause(), retryAfterSeconds, headers, unsupportedTools, pausedProviders, next);
    }

    public AiErrorKind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public java.util.Map<String, java.util.List<String>> headers() {
        return headers;
    }

    /**
     * The provider rejected a native {@code tools} payload. Dialogue retries once without tools
     * and does not read an action name out of the reply text.
     */
    public boolean unsupportedTools() {
        return unsupportedTools;
    }

    /**
     * Providers whose 401, 402, or 429 produced this failure. Empty when the caller did not name one.
     */
    public java.util.Set<String> pausedProviders() {
        return pausedProviders;
    }

    /**
     * @return this exception, or a copy that also names {@code providerId}
     */
    public AiRequestException withPausedProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return this;
        }
        String normalized = providerId.trim().toLowerCase(java.util.Locale.ROOT);
        if (pausedProviders.contains(normalized)) {
            return this;
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(pausedProviders);
        merged.add(normalized);
        return copy(getMessage(), merged);
    }

    private AiRequestException copy(String message, java.util.Set<String> providers) {
        return new AiRequestException(
                kind,
                status,
                message,
                getCause(),
                retryAfterSeconds,
                headers,
                unsupportedTools,
                providers,
                usage
        );
    }

    /**
     * Provider ids named on this exception or any nested {@link AiRequestException} cause.
     */
    public static java.util.Set<String> pausedProvidersOf(Throwable error) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof AiRequestException typed) {
                ids.addAll(typed.pausedProviders);
            }
            current = current.getCause();
        }
        return ids.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }
}
