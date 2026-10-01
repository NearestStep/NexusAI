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
        super(message, cause);
        this.kind = kind == null ? AiErrorKind.OTHER : kind;
        this.status = status;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
        this.headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        this.unsupportedTools = unsupportedTools;
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
}
