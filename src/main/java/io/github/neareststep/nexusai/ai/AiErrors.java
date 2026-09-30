package io.github.neareststep.nexusai.ai;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Maps provider failures onto {@link AiErrorKind} without throwing.
 */
public final class AiErrors {

    private AiErrors() {
    }

    public static AiErrorKind classify(Throwable error) {
        AiRequestException typed = find(error);
        if (typed != null) {
            return typed.kind();
        }
        Throwable root = unwrap(error);
        if (root instanceof HttpTimeoutException || root instanceof HttpConnectTimeoutException) {
            return AiErrorKind.TIMEOUT;
        }
        String message = root == null || root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timed out") || message.contains("timeout")) {
            return AiErrorKind.TIMEOUT;
        }
        return classifyHttp(parseStatus(message), message, message.contains("<html") || message.contains("<!doctype"));
    }

    public static AiErrorKind classifyHttp(int status, String body, boolean html) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 429) {
            if (lower.contains("insufficient_quota") || lower.contains("quota")) {
                return AiErrorKind.QUOTA;
            }
            return AiErrorKind.RATE_LIMIT;
        }
        if (status == 402 || lower.contains("insufficient balance") || lower.contains("insufficient_quota")) {
            return AiErrorKind.QUOTA;
        }
        if (status == 401) {
            return AiErrorKind.BAD_KEY;
        }
        if (status == 403 && !html) {
            return AiErrorKind.BAD_KEY;
        }
        if (isUnknownModel(lower)) {
            return AiErrorKind.UNKNOWN_MODEL;
        }
        return AiErrorKind.OTHER;
    }

    public static AiRequestException find(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 8) {
            if (current instanceof AiRequestException typed) {
                return typed;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * True when the failure was decided locally because no API key is configured.
     * No HTTP status was returned, so this is not a provider rejection.
     */
    public static boolean localMissingKey(Throwable error) {
        AiRequestException typed = find(error);
        if (typed == null || typed.status() != 0) {
            return false;
        }
        String message = typed.getMessage();
        return message != null && message.contains("API key is not configured");
    }

    public static String detail(Throwable error) {
        AiRequestException typed = find(error);
        if (typed != null && typed.getMessage() != null && !typed.getMessage().isBlank()) {
            return typed.getMessage();
        }
        Throwable root = unwrap(error);
        if (root == null || root.getMessage() == null) {
            return "";
        }
        return root.getMessage();
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        int guard = 0;
        while (current instanceof CompletionException && current.getCause() != null && guard++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isUnknownModel(String lower) {
        return lower.contains("model_not_found")
                || lower.contains("invalid_model")
                || lower.contains("model_not_available")
                || lower.contains("unknown model")
                || lower.contains("invalid model")
                || lower.contains("no such model")
                || (lower.contains("model") && lower.contains("does not exist"));
    }

    private static int parseStatus(String message) {
        int marker = message.indexOf("http ");
        if (marker < 0) {
            return 0;
        }
        int start = marker + 5;
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(message.substring(start, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
