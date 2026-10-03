package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Shared admission for every outgoing completion: server/player rate limits,
 * per-prompt backoff after errors, and a provider-wide pause after 401/402/429.
 */
public final class RequestGate {

    private final RateLimiter rateLimiter;
    private final long backoffInitialMillis;
    private final long backoffMaxMillis;
    private final long rateLimitPauseMillis;
    private final long authPauseMillis;
    private final LongSupplier clock;
    /** Empty-after-sanitising delays: 5 min, 15 min, 30 min, then 60 min. */
    private static final long[] EMPTY_REPLY_BACKOFF_MILLIS = {
            5L * 60_000L,
            15L * 60_000L,
            30L * 60_000L,
            60L * 60_000L
    };
    /**
     * One fixed hold after a markup-only reply. It does not climb, and it is not the
     * empty-reply ladder. Placeholders and the pool serve fallback until it ends.
     * {@code /nai test} does not start it and is not blocked by it.
     */
    static final long MARKUP_ONLY_BACKOFF_MILLIS = 30_000L;

    private final ConcurrentHashMap<String, Backoff> backoffByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> failureEpochByKey = new ConcurrentHashMap<>();
    private final AtomicLong pauseGeneration = new AtomicLong();
    private volatile long pausedUntil;
    private volatile AiErrorKind pauseKind = AiErrorKind.OTHER;

    public RequestGate(
            RateLimiter rateLimiter,
            long backoffInitialMillis,
            long backoffMaxMillis,
            long rateLimitPauseMillis,
            long authPauseMillis,
            LongSupplier clock
    ) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.backoffInitialMillis = Math.max(0L, backoffInitialMillis);
        this.backoffMaxMillis = Math.max(this.backoffInitialMillis, backoffMaxMillis);
        this.rateLimitPauseMillis = Math.max(0L, rateLimitPauseMillis);
        this.authPauseMillis = Math.max(0L, authPauseMillis);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static RequestGate fromConfig(RateLimiter rateLimiter, PluginConfig config) {
        Objects.requireNonNull(config, "config");
        return new RequestGate(
                rateLimiter,
                config.getErrorBackoffInitialSeconds() * 1000L,
                config.getErrorBackoffMaxSeconds() * 1000L,
                config.getProviderPauseSeconds() * 1000L,
                config.getAuthPauseSeconds() * 1000L,
                System::currentTimeMillis
        );
    }

    /**
     * High limits and zero delays. Used by tests that are not exercising admission.
     */
    public static RequestGate permissive() {
        return new RequestGate(
                new RateLimiter(1_000_000, 1_000_000),
                0L,
                0L,
                0L,
                0L,
                System::currentTimeMillis
        );
    }

    /**
     * @param bypassBackoffAndPause admin probe ({@code /nai test}) still spends a rate-limit slot
     * @return empty when the call may hit the provider
     */
    public Optional<String> tryAdmit(UUID playerId, String admissionKey, boolean bypassBackoffAndPause) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        if (!bypassBackoffAndPause && isBlocked(admissionKey)) {
            if (isPaused()) {
                return Optional.of(pauseMessage());
            }
            return Optional.of("Backing off after a provider error for this prompt");
        }
        if (!rateLimiter.tryAcquire(playerId)) {
            return Optional.of("Local rate limit reached");
        }
        return Optional.empty();
    }

    public boolean isBlocked(String admissionKey) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        if (isPaused()) {
            return true;
        }
        Backoff backoff = backoffByKey.get(admissionKey);
        return backoff != null && clock.getAsLong() < backoff.untilMillis;
    }

    public boolean isPaused() {
        return clock.getAsLong() < pausedUntil;
    }

    public AiErrorKind pauseKind() {
        return isPaused() ? pauseKind : null;
    }

    public long pauseRemainingMillis() {
        return Math.max(0L, pausedUntil - clock.getAsLong());
    }

    public long pauseStamp() {
        return pauseGeneration.get();
    }

    public long failureEpoch(String admissionKey) {
        return failureEpochByKey.getOrDefault(admissionKey, 0L);
    }

    /**
     * Drops per-prompt backoff, including an empty-reply ladder.
     * {@code /nai reload} builds a new gate and calls this so a changed prompt can be sent again.
     */
    public void resetBackoff() {
        backoffByKey.clear();
        failureEpochByKey.clear();
    }

    public long blockedForMillis(String admissionKey) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        long now = clock.getAsLong();
        long pauseLeft = Math.max(0L, pausedUntil - now);
        Backoff backoff = backoffByKey.get(admissionKey);
        long backoffLeft = backoff == null ? 0L : Math.max(0L, backoff.untilMillis - now);
        return Math.max(pauseLeft, backoffLeft);
    }

    /**
     * Epoch millis when this prompt may be sent again, or {@code 0} when nothing is holding it.
     */
    public long blockedUntilMillis(String admissionKey) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        long until = Math.max(0L, pausedUntil);
        Backoff backoff = backoffByKey.get(admissionKey);
        if (backoff != null) {
            until = Math.max(until, backoff.untilMillis);
        }
        return until;
    }

    public void recordSuccess(String admissionKey, long pauseStamp, long failureEpoch) {
        recordSuccess(admissionKey, pauseStamp, failureEpoch, true);
    }

    /**
     * @param clearPause {@code false} for {@code /nai test}, which must not shorten a provider pause
     */
    public void recordSuccess(String admissionKey, long pauseStamp, long failureEpoch, boolean clearPause) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        long currentEpoch = failureEpochByKey.getOrDefault(admissionKey, 0L);
        if (currentEpoch == failureEpoch) {
            // A non-empty reply drops the empty-reply ladder and a generic backoff.
            // A markup-only hold stays until its timer or resetBackoff (/nai reload).
            long now = clock.getAsLong();
            backoffByKey.compute(admissionKey, (key, backoff) -> {
                if (backoff != null && backoff.markupOnly && now < backoff.untilMillis) {
                    return backoff;
                }
                return null;
            });
        }
        if (clearPause && pauseGeneration.get() == pauseStamp) {
            pausedUntil = 0L;
            pauseKind = null;
        }
    }

    public void recordFailure(String admissionKey, AiErrorKind kind) {
        recordFailure(admissionKey, kind, 0L, true);
    }

    /**
     * @param retryAfterSeconds {@code Retry-After} delta-seconds; the provider pause is at least the configured length
     */
    public void recordFailure(String admissionKey, AiErrorKind kind, long retryAfterSeconds) {
        recordFailure(admissionKey, kind, retryAfterSeconds, true);
    }

    /**
     * @param armPause {@code false} for {@code /nai test}: a probe may observe 401/402/429 but must not start or extend the provider pause
     */
    public void recordFailure(String admissionKey, AiErrorKind kind, long retryAfterSeconds, boolean armPause) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        if (kind == AiErrorKind.MARKUP_ONLY) {
            if (!armPause) {
                return;
            }
            long now = clock.getAsLong();
            backoffByKey.compute(admissionKey, (key, previous) -> {
                if (previous != null && now < previous.untilMillis) {
                    return previous;
                }
                int genericAttempt = previous == null ? 0 : previous.attempt;
                int emptyReplyAttempt = previous == null ? 0 : previous.emptyReplyAttempt;
                return new Backoff(now + MARKUP_ONLY_BACKOFF_MILLIS, genericAttempt, emptyReplyAttempt, true);
            });
            return;
        }
        if (kind == null || kind == AiErrorKind.LOCAL_LIMIT || kind == AiErrorKind.REJECTED) {
            return;
        }
        long now = clock.getAsLong();
        failureEpochByKey.merge(admissionKey, 1L, Long::sum);
        if (kind == AiErrorKind.EMPTY_REPLY) {
            backoffByKey.compute(admissionKey, (key, previous) -> {
                // One open wait is one attempt. A pool refill sends several requests together;
                // each empty reply must not climb 5 → 15 → 30 on its own.
                if (previous != null && previous.emptyReplyAttempt > 0 && now < previous.untilMillis) {
                    return previous;
                }
                int emptyAttempt = previous == null || previous.emptyReplyAttempt == 0
                        ? 1
                        : previous.emptyReplyAttempt + 1;
                int index = Math.min(emptyAttempt, EMPTY_REPLY_BACKOFF_MILLIS.length) - 1;
                int genericAttempt = previous == null ? 0 : previous.attempt;
                return new Backoff(now + EMPTY_REPLY_BACKOFF_MILLIS[index], genericAttempt, emptyAttempt, false);
            });
            return;
        }
        backoffByKey.compute(admissionKey, (key, previous) -> {
            int attempt = previous == null ? 1 : previous.attempt + 1;
            int emptyReplyAttempt = previous == null ? 0 : previous.emptyReplyAttempt;
            long multiplier = 1L << Math.min(attempt - 1, 10);
            long delay = backoffInitialMillis == 0L
                    ? 0L
                    : Math.min(backoffMaxMillis, backoffInitialMillis * multiplier);
            return new Backoff(now + delay, attempt, emptyReplyAttempt, false);
        });
        if (armPause && kind.pausesProvider()) {
            long pause = kind == AiErrorKind.RATE_LIMIT ? rateLimitPauseMillis : authPauseMillis;
            if (kind == AiErrorKind.RATE_LIMIT && retryAfterSeconds > 0L) {
                pause = Math.max(pause, retryAfterSeconds * 1000L);
            }
            pausedUntil = now + pause;
            pauseKind = kind;
            pauseGeneration.incrementAndGet();
        }
    }

    private String pauseMessage() {
        long seconds = Math.max(1L, (pauseRemainingMillis() + 999L) / 1000L);
        String why = switch (pauseKind == null ? AiErrorKind.OTHER : pauseKind) {
            case RATE_LIMIT -> "rate limit";
            case QUOTA -> "quota";
            case BAD_KEY -> "authentication";
            default -> "provider error";
        };
        return "Provider requests are paused (" + why + ") for " + seconds + "s";
    }

    private static final class Backoff {
        private final long untilMillis;
        /** Generic error-backoff step. Zero when only empty replies have been recorded. */
        private final int attempt;
        /** Empty-reply step. Zero when this prompt has not returned an empty reply. */
        private final int emptyReplyAttempt;
        /** True only for the fixed 30s markup-only hold. Success does not clear it. */
        private final boolean markupOnly;

        private Backoff(long untilMillis, int attempt, int emptyReplyAttempt, boolean markupOnly) {
            this.untilMillis = untilMillis;
            this.attempt = attempt;
            this.emptyReplyAttempt = emptyReplyAttempt;
            this.markupOnly = markupOnly;
        }
    }
}
