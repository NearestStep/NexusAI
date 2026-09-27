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

    public long blockedForMillis(String admissionKey) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        long now = clock.getAsLong();
        long pauseLeft = Math.max(0L, pausedUntil - now);
        Backoff backoff = backoffByKey.get(admissionKey);
        long backoffLeft = backoff == null ? 0L : Math.max(0L, backoff.untilMillis - now);
        return Math.max(pauseLeft, backoffLeft);
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
            backoffByKey.remove(admissionKey);
        }
        if (clearPause && pauseGeneration.get() == pauseStamp) {
            pausedUntil = 0L;
            pauseKind = null;
        }
    }

    public void recordFailure(String admissionKey, AiErrorKind kind) {
        recordFailure(admissionKey, kind, 0L);
    }

    /**
     * @param retryAfterSeconds {@code Retry-After} delta-seconds; the provider pause is at least the configured length
     */
    public void recordFailure(String admissionKey, AiErrorKind kind, long retryAfterSeconds) {
        Objects.requireNonNull(admissionKey, "admissionKey");
        if (kind == null || kind == AiErrorKind.LOCAL_LIMIT) {
            return;
        }
        long now = clock.getAsLong();
        failureEpochByKey.merge(admissionKey, 1L, Long::sum);
        backoffByKey.compute(admissionKey, (key, previous) -> {
            int attempt = previous == null ? 1 : previous.attempt + 1;
            long multiplier = 1L << Math.min(attempt - 1, 10);
            long delay = backoffInitialMillis == 0L
                    ? 0L
                    : Math.min(backoffMaxMillis, backoffInitialMillis * multiplier);
            return new Backoff(now + delay, attempt);
        });
        if (kind.pausesProvider()) {
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
        private final int attempt;

        private Backoff(long untilMillis, int attempt) {
            this.untilMillis = untilMillis;
            this.attempt = attempt;
        }
    }
}
