package io.github.neareststep.nexusai.api;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Why a generation did not return model text.
 * <p>
 * {@link #message()} is masked and at most 300 Unicode code points. It does not include the
 * API key, HTTP headers, or the provider body beyond that clip.
 */
public final class GenerationError {

    private static final int MAX_MESSAGE_CODE_POINTS = 300;

    private final NexusErrorKind kind;
    private final String message;
    private final int httpStatus;
    private final long retryAfterSeconds;

    private GenerationError(NexusErrorKind kind, String message, int httpStatus, long retryAfterSeconds) {
        this.kind = kind == null ? NexusErrorKind.PROVIDER_ERROR : kind;
        this.message = clip(message);
        this.httpStatus = Math.max(0, httpStatus);
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }

    /** Not part of the plugin API. The message is clipped here; the caller masks secrets first. */
    @ApiStatus.Internal
    public static GenerationError of(NexusErrorKind kind, String message, int httpStatus, long retryAfterSeconds) {
        return new GenerationError(kind, message, httpStatus, retryAfterSeconds);
    }

    public NexusErrorKind kind() {
        return kind;
    }

    /** Masked, at most 300 Unicode code points. */
    public String message() {
        return message;
    }

    /** HTTP status, or {@code 0} when the provider did not return one. */
    public int httpStatus() {
        return httpStatus;
    }

    /** {@code Retry-After} in seconds, or empty when the provider did not send one. */
    public OptionalLong retryAfterSeconds() {
        return retryAfterSeconds == 0L ? OptionalLong.empty() : OptionalLong.of(retryAfterSeconds);
    }

    private static String clip(String message) {
        String text = message == null ? "" : message;
        if (text.codePointCount(0, text.length()) <= MAX_MESSAGE_CODE_POINTS) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, MAX_MESSAGE_CODE_POINTS));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenerationError that)) {
            return false;
        }
        return httpStatus == that.httpStatus
                && retryAfterSeconds == that.retryAfterSeconds
                && kind == that.kind
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, message, httpStatus, retryAfterSeconds);
    }

    @Override
    public String toString() {
        return "GenerationError{kind=" + kind
                + ", httpStatus=" + httpStatus
                + ", retryAfterSeconds=" + retryAfterSeconds
                + ", message=" + message
                + "}";
    }
}
