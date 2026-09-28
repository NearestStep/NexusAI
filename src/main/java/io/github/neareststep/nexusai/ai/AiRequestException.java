package io.github.neareststep.nexusai.ai;

/**
 * Failure of a single completion, with a status code when the provider returned one.
 */
public final class AiRequestException extends RuntimeException {

    private final AiErrorKind kind;
    private final int status;
    private final long retryAfterSeconds;

    public AiRequestException(AiErrorKind kind, int status, String message, Throwable cause) {
        this(kind, status, message, cause, 0L);
    }

    public AiRequestException(AiErrorKind kind, int status, String message, Throwable cause, long retryAfterSeconds) {
        super(message, cause);
        this.kind = kind == null ? AiErrorKind.OTHER : kind;
        this.status = status;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
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
}
