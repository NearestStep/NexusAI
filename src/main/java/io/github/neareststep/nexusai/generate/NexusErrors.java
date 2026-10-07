package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.api.GenerationError;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.SecretMask;

/**
 * Maps transport failures onto {@link NexusErrorKind} and masks the message.
 */
final class NexusErrors {

    static final String NOT_CONFIGURED = "NexusAI is not configured for API requests";
    static final String UNKNOWN_PROMPT = "Unknown prompt id";
    static final String PLAYER_UNAVAILABLE = "Player is not available";
    static final String SHUTDOWN = "NexusAI is shutting down";
    static final String CANCELLED = "Cancelled";

    private NexusErrors() {
    }

    static GenerationError of(PluginConfig config, NexusErrorKind kind, String message, int httpStatus, long retryAfter) {
        return GenerationError.of(kind, mask(config, message), httpStatus, retryAfter);
    }

    static GenerationError notConfigured(PluginConfig config) {
        return of(config, NexusErrorKind.NOT_CONFIGURED, NOT_CONFIGURED, 0, 0L);
    }

    static GenerationError fromGate(PluginConfig config, String message) {
        String text = message == null ? "" : message;
        NexusErrorKind kind;
        if (text.startsWith("Provider requests are paused")) {
            kind = NexusErrorKind.PAUSED;
        } else if (text.startsWith("Backing off")) {
            kind = NexusErrorKind.BACKOFF;
        } else {
            kind = NexusErrorKind.LOCAL_LIMIT;
        }
        return of(config, kind, text, 0, 0L);
    }

    static GenerationError fromThrowable(PluginConfig config, Throwable error) {
        if (HttpPool.isQueueFull(error)) {
            return of(config, NexusErrorKind.QUEUE_FULL, HttpPool.QUEUE_FULL, 0, 0L);
        }
        if (AiErrors.localMissingKey(error)) {
            return notConfigured(config);
        }
        AiErrorKind kind = AiErrors.classify(error);
        AiRequestException typed = AiErrors.find(error);
        int status = typed == null ? 0 : typed.status();
        long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
        return of(config, map(kind), AiErrors.detail(error), status, retryAfter);
    }

    static NexusErrorKind map(AiErrorKind kind) {
        if (kind == null) {
            return NexusErrorKind.PROVIDER_ERROR;
        }
        return switch (kind) {
            case RATE_LIMIT -> NexusErrorKind.RATE_LIMIT;
            case QUOTA -> NexusErrorKind.PROVIDER_QUOTA;
            case BAD_KEY -> NexusErrorKind.BAD_KEY;
            case UNKNOWN_MODEL -> NexusErrorKind.UNKNOWN_MODEL;
            case TIMEOUT -> NexusErrorKind.TIMEOUT;
            case LOCAL_LIMIT -> NexusErrorKind.LOCAL_LIMIT;
            case REJECTED -> NexusErrorKind.REJECTED;
            case EMPTY_REPLY -> NexusErrorKind.EMPTY_REPLY;
            case MARKUP_ONLY -> NexusErrorKind.MARKUP_ONLY;
            case OTHER -> NexusErrorKind.PROVIDER_ERROR;
        };
    }

    static String mask(PluginConfig config, String message) {
        Iterable<String> secrets = config == null ? java.util.List.of() : config.configuredSecrets();
        String masked = SecretMask.redact(message == null ? "" : message, secrets);
        return masked == null ? "" : masked;
    }
}
