package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.GenerationError;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;

import java.time.Duration;

/**
 * Which origins emit a failure event, and the mapping from a transport error onto
 * {@link GenerationError}. Admission refusals for placeholders, talk, and the pool stay quiet.
 */
public final class GenerationEvents {

    private GenerationEvents() {
    }

    /**
     * Placeholder, talk (including a greeting), and pool refills do not emit Pre or Fail when
     * limits, a quota, or a pause refuse the call. API emits Fail for every failure. Prewarm,
     * {@code /nai test}, and summaries are not in that quiet set, so a refusal emits Fail and not Pre.
     * Moderation never emits Pre, Post, or Fail.
     */
    public static boolean admissionEmitsFail(RequestOrigin origin) {
        if (origin == null || origin == RequestOrigin.MODERATION) {
            return false;
        }
        return origin != RequestOrigin.PLACEHOLDER
                && origin != RequestOrigin.POOL
                && origin != RequestOrigin.TALK
                && origin != RequestOrigin.TALK_GREETING;
    }

    /** Fail for an admission refusal when {@link #admissionEmitsFail} is true. No Pre was fired. */
    public static void admissionRefused(CallTrace trace, NexusErrorKind kind, String message) {
        if (trace == null || !admissionEmitsFail(trace.origin())) {
            return;
        }
        EventDispatcher.get().fail(
                trace,
                GenerationError.of(kind == null ? NexusErrorKind.LOCAL_LIMIT : kind, EventTexts.message(message), 0, 0L),
                0,
                Duration.ZERO);
    }

    /**
     * Fail when this request already fired Pre, when the listener cancelled it, or when the origin
     * reports admission failures. Talk and placeholders that never reached Pre stay quiet.
     */
    public static void failIfNeeded(CallTrace trace, Throwable error) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        EventDispatcher dispatcher = EventDispatcher.get();
        boolean opened = dispatcher.opened(trace.requestId());
        PreCancelled cancelled = PreCancelled.find(error);
        if (cancelled == null && !opened && !admissionEmitsFail(trace.origin())) {
            return;
        }
        dispatcher.fail(trace, from(error), dispatcher.attempts(trace.requestId()), latency(trace));
    }

    /** Post using the attempt facts recorded for this request, then drops them. */
    public static void postText(CallTrace trace, String text) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        EventDispatcher.get().postFromOpen(trace, text);
    }

    public static int estimateTokens(String text) {
        return ResponseUsage.tokensFromChars(ResponseUsage.chars(text));
    }

    public static GenerationError from(Throwable error) {
        PreCancelled cancelled = PreCancelled.find(error);
        if (cancelled != null) {
            return GenerationError.of(NexusErrorKind.CANCELLED, EventTexts.message(cancelled.reason()), 0, 0L);
        }
        if (HttpPool.isQueueFull(error)) {
            return GenerationError.of(NexusErrorKind.QUEUE_FULL, EventTexts.message(HttpPool.QUEUE_FULL), 0, 0L);
        }
        if (AiErrors.localMissingKey(error)) {
            return GenerationError.of(NexusErrorKind.NOT_CONFIGURED, EventTexts.message("NexusAI API key is not configured"), 0, 0L);
        }
        AiErrorKind kind = AiErrors.classify(error);
        AiRequestException typed = AiErrors.find(error);
        int status = typed == null ? 0 : typed.status();
        long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
        String detail = AiErrors.detail(error);
        return GenerationError.of(map(kind, detail), EventTexts.message(detail), status, retryAfter);
    }

    public static NexusErrorKind providerKind(AiErrorKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case RATE_LIMIT -> NexusErrorKind.RATE_LIMIT;
            case QUOTA -> NexusErrorKind.PROVIDER_QUOTA;
            case BAD_KEY -> NexusErrorKind.BAD_KEY;
            case UNKNOWN_MODEL -> NexusErrorKind.UNKNOWN_MODEL;
            case TIMEOUT -> NexusErrorKind.TIMEOUT;
            case OTHER -> NexusErrorKind.PROVIDER_ERROR;
            default -> null;
        };
    }

    static Duration latency(CallTrace trace) {
        if (trace == null) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - trace.startedNanos()));
    }

    private static NexusErrorKind map(AiErrorKind kind, String detail) {
        if (kind == null) {
            return NexusErrorKind.PROVIDER_ERROR;
        }
        NexusErrorKind mapped = switch (kind) {
            case RATE_LIMIT -> NexusErrorKind.RATE_LIMIT;
            case QUOTA -> NexusErrorKind.PROVIDER_QUOTA;
            case BAD_KEY -> NexusErrorKind.BAD_KEY;
            case UNKNOWN_MODEL -> NexusErrorKind.UNKNOWN_MODEL;
            case TIMEOUT -> NexusErrorKind.TIMEOUT;
            case LOCAL_LIMIT -> NexusErrorKind.LOCAL_LIMIT;
            case LOCAL_QUOTA -> NexusErrorKind.QUOTA_EXCEEDED;
            case REJECTED -> NexusErrorKind.REJECTED;
            case EMPTY_REPLY -> NexusErrorKind.EMPTY_REPLY;
            case MARKUP_ONLY -> NexusErrorKind.MARKUP_ONLY;
            case OTHER -> NexusErrorKind.PROVIDER_ERROR;
        };
        if (mapped == NexusErrorKind.LOCAL_LIMIT
                && detail != null
                && detail.contains("All model-queue entries are exhausted")) {
            return NexusErrorKind.QUOTA_EXCEEDED;
        }
        return mapped;
    }
}
