package io.github.neareststep.nexusai.ai;

/**
 * Failure of a single completion, with a status code when the provider returned one.
 */
public final class AiRequestException extends RuntimeException {

    private final AiErrorKind kind;
    private final int status;
    private final long retryAfterSeconds;
    private final java.util.Map<String, java.util.List<String>> headers;

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
        super(message, cause);
        this.kind = kind == null ? AiErrorKind.OTHER : kind;
        this.status = status;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        this.headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
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
}
