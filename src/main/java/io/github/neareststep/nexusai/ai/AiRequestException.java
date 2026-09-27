package io.github.neareststep.nexusai.ai;

/**
 * Failure of a single completion, with a status code when the provider returned one.
 */
public final class AiRequestException extends RuntimeException {

    private final AiErrorKind kind;
    private final int status;

    public AiRequestException(AiErrorKind kind, int status, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? AiErrorKind.OTHER : kind;
        this.status = status;
    }

    public AiErrorKind kind() {
        return kind;
    }

    public int status() {
        return status;
    }
}
