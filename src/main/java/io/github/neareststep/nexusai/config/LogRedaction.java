package io.github.neareststep.nexusai.config;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a file failure so a data-directory path cannot carry a key into the console.
 * The warning names the exception class and a masked message. The stack is logged at
 * {@link Level#FINE} with those same messages, and the original throwable is not attached.
 */
public final class LogRedaction {

    private LogRedaction() {
    }

    public static void warning(Logger logger, String summary, Throwable error, Iterable<String> secrets) {
        if (logger == null) {
            return;
        }
        logger.warning(summaryWithCause(summary, error, secrets));
        if (error != null) {
            logger.log(Level.FINE, summary, redactThrowable(error, secrets));
        }
    }

    public static String summaryWithCause(String summary, Throwable error, Iterable<String> secrets) {
        String detail = detail(error, secrets);
        if (detail.isEmpty()) {
            return summary == null ? "" : summary;
        }
        return summary + " (" + detail + ")";
    }

    /** Exception class and masked message, on one line. Empty when {@code error} is null. */
    public static String detail(Throwable error, Iterable<String> secrets) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        String redacted = SecretMask.redact(message == null ? "" : flatten(message), secrets);
        String name = error.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            name = error.getClass().getName();
        }
        if (redacted.isBlank()) {
            return name;
        }
        return name + ": " + redacted;
    }

    /**
     * A throwable whose message and cause chain are masked. Stack frames are the original frames.
     * The original throwable is not reachable from the result.
     */
    public static Throwable redactThrowable(Throwable error, Iterable<String> secrets) {
        if (error == null) {
            return null;
        }
        Throwable cause = error.getCause();
        Throwable redactedCause = cause == null || cause == error ? null : redactThrowable(cause, secrets);
        RedactedFailure failure = new RedactedFailure(detail(error, secrets), redactedCause);
        failure.setStackTrace(error.getStackTrace());
        for (Throwable suppressed : error.getSuppressed()) {
            if (suppressed != null && suppressed != error) {
                failure.addSuppressed(redactThrowable(suppressed, secrets));
            }
        }
        return failure;
    }

    private static String flatten(String message) {
        return message.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
    }

    private static final class RedactedFailure extends Exception {
        private RedactedFailure(String message, Throwable cause) {
            super(message, cause, true, true);
        }
    }
}
