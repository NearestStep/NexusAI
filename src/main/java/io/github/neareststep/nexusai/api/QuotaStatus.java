package io.github.neareststep.nexusai.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Today's token and request spend for one API consumer, and the daily caps when quotas are on.
 * <p>
 * Call {@link NexusAIApi#quota} from any thread. The numbers are a memory snapshot.
 * An empty {@link #tokensPerDay()} or {@link #requestsPerDay()} means there is no cap
 * (quotas are off, or the configured limit is 0).
 * <p>
 * On Folia this type does not read region state.
 */
public final class QuotaStatus {

    private final LocalDate day;
    private final long tokensToday;
    private final long tokensPerDay;
    private final boolean tokensLimited;
    private final long requestsToday;
    private final long requestsPerDay;
    private final boolean requestsLimited;

    private QuotaStatus(
            LocalDate day,
            long tokensToday,
            long tokensPerDay,
            boolean tokensLimited,
            long requestsToday,
            long requestsPerDay,
            boolean requestsLimited
    ) {
        this.day = day == null ? LocalDate.now() : day;
        this.tokensToday = Math.max(0L, tokensToday);
        this.tokensPerDay = Math.max(0L, tokensPerDay);
        this.tokensLimited = tokensLimited && this.tokensPerDay > 0L;
        this.requestsToday = Math.max(0L, requestsToday);
        this.requestsPerDay = Math.max(0L, requestsPerDay);
        this.requestsLimited = requestsLimited && this.requestsPerDay > 0L;
    }

    /** Not part of the plugin API. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static QuotaStatus of(
            LocalDate day,
            long tokensToday,
            long tokensPerDay,
            boolean tokensLimited,
            long requestsToday,
            long requestsPerDay,
            boolean requestsLimited
    ) {
        return new QuotaStatus(day, tokensToday, tokensPerDay, tokensLimited, requestsToday, requestsPerDay, requestsLimited);
    }

    public static QuotaStatus empty(LocalDate day) {
        return new QuotaStatus(day, 0L, 0L, false, 0L, 0L, false);
    }

    public LocalDate day() {
        return day;
    }

    public long tokensToday() {
        return tokensToday;
    }

    /** Empty when this consumer has no daily token cap. */
    public OptionalLong tokensPerDay() {
        return tokensLimited ? OptionalLong.of(tokensPerDay) : OptionalLong.empty();
    }

    public long requestsToday() {
        return requestsToday;
    }

    /** Empty when this consumer has no daily request cap. */
    public OptionalLong requestsPerDay() {
        return requestsLimited ? OptionalLong.of(requestsPerDay) : OptionalLong.empty();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof QuotaStatus that)) {
            return false;
        }
        return tokensToday == that.tokensToday
                && tokensPerDay == that.tokensPerDay
                && tokensLimited == that.tokensLimited
                && requestsToday == that.requestsToday
                && requestsPerDay == that.requestsPerDay
                && requestsLimited == that.requestsLimited
                && day.equals(that.day);
    }

    @Override
    public int hashCode() {
        return Objects.hash(day, tokensToday, tokensPerDay, tokensLimited, requestsToday, requestsPerDay, requestsLimited);
    }

    @Override
    public String toString() {
        return "QuotaStatus{day=" + day
                + ", tokensToday=" + tokensToday
                + ", tokensPerDay=" + (tokensLimited ? Long.toString(tokensPerDay) : "")
                + ", requestsToday=" + requestsToday
                + ", requestsPerDay=" + (requestsLimited ? Long.toString(requestsPerDay) : "")
                + "}";
    }
}
