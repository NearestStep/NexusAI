package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.ai.ResponseUsage;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Token counts for one generation.
 * <p>
 * {@link #reported()} means the provider sent a {@code usage} object.
 * {@link #estimated()} means that object was missing and NexusAI filled the counts from the
 * text length. Cache hits and in-flight joins use {@link #none()}: the tokens are counted once,
 * on the call that talked to the model.
 */
public final class TokenUsage {

    private static final TokenUsage NONE = new TokenUsage(0, 0, 0, false, false, OptionalDouble.empty());

    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final boolean reported;
    private final boolean estimated;
    private final OptionalDouble cost;

    private TokenUsage(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean reported,
            boolean estimated,
            OptionalDouble cost
    ) {
        this.promptTokens = Math.max(0, promptTokens);
        this.completionTokens = Math.max(0, completionTokens);
        this.totalTokens = Math.max(0, totalTokens);
        this.reported = reported;
        this.estimated = reported ? false : estimated;
        this.cost = cost == null || cost.isEmpty() || !Double.isFinite(cost.getAsDouble())
                ? OptionalDouble.empty()
                : cost;
    }

    /** No provider usage and no estimate. The same instance is returned every time. */
    public static TokenUsage none() {
        return NONE;
    }

    /** Copies a transport usage value. Not part of the plugin API. */
    @ApiStatus.Internal
    public static TokenUsage from(ResponseUsage usage) {
        if (usage == null || (!usage.reported() && !usage.estimated()
                && usage.promptTokens() == 0 && usage.completionTokens() == 0 && usage.totalTokens() == 0)) {
            return NONE;
        }
        return new TokenUsage(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                usage.reported(),
                usage.estimated(),
                usage.cost());
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public int totalTokens() {
        return totalTokens;
    }

    /** The provider sent a {@code usage} object. */
    public boolean reported() {
        return reported;
    }

    /** The provider sent no {@code usage} object and the counts are a length estimate. */
    public boolean estimated() {
        return estimated;
    }

    /** {@code usage.cost} when the provider sent a finite number. */
    public OptionalDouble cost() {
        return cost;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TokenUsage that)) {
            return false;
        }
        return promptTokens == that.promptTokens
                && completionTokens == that.completionTokens
                && totalTokens == that.totalTokens
                && reported == that.reported
                && estimated == that.estimated
                && Double.compare(cost.orElse(Double.NaN), that.cost.orElse(Double.NaN)) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(promptTokens, completionTokens, totalTokens, reported, estimated, cost.orElse(Double.NaN));
    }

    @Override
    public String toString() {
        return "TokenUsage{promptTokens=" + promptTokens
                + ", completionTokens=" + completionTokens
                + ", totalTokens=" + totalTokens
                + ", reported=" + reported
                + ", estimated=" + estimated
                + ", cost=" + (cost.isPresent() ? cost.getAsDouble() : "")
                + "}";
    }
}
