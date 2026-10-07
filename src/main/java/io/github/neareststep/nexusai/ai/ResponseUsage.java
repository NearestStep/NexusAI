package io.github.neareststep.nexusai.ai;

import java.util.OptionalDouble;

/**
 * Token counts for one HTTP completion.
 * <p>
 * {@code reported} means the provider sent a {@code usage} object. When that object is missing,
 * {@link #estimate(int, int)} fills the counts from characters divided by four and sets
 * {@code estimated}. A later quota mode can ignore those counts. This type does not write
 * {@code token-usage.yml}.
 */
public record ResponseUsage(
        int promptTokens,
        int completionTokens,
        int totalTokens,
        boolean reported,
        boolean estimated,
        OptionalDouble cost
) {
    public ResponseUsage {
        promptTokens = clamp(promptTokens);
        completionTokens = clamp(completionTokens);
        totalTokens = clamp(totalTokens);
        if (reported) {
            estimated = false;
        }
        if (cost == null) {
            cost = OptionalDouble.empty();
        }
    }

    /** No provider usage and no estimate. */
    public static ResponseUsage none() {
        return new ResponseUsage(0, 0, 0, false, false, OptionalDouble.empty());
    }

    /**
     * Counts from a provider {@code usage} object. A missing total becomes {@code prompt + completion}.
     * A total below that sum is raised to the sum. Negative numbers are zero. {@code cost} is kept
     * only when the provider sent a finite number.
     */
    public static ResponseUsage reported(int promptTokens, int completionTokens, Integer totalTokens, Double cost) {
        int prompt = clamp(promptTokens);
        int completion = clamp(completionTokens);
        int sum = saturatingAdd(prompt, completion);
        int total = totalTokens == null ? sum : Math.max(clamp(totalTokens), sum);
        OptionalDouble parsedCost = cost == null || !Double.isFinite(cost)
                ? OptionalDouble.empty()
                : OptionalDouble.of(cost);
        return new ResponseUsage(prompt, completion, total, true, false, parsedCost);
    }

    /**
     * {@code ceil(characters / 4)} for the request messages and the raw completion.
     * The estimate is marked {@code estimated} and is not {@code reported}.
     */
    public static ResponseUsage estimate(int promptChars, int completionChars) {
        int prompt = tokensFromChars(promptChars);
        int completion = tokensFromChars(completionChars);
        return new ResponseUsage(prompt, completion, saturatingAdd(prompt, completion), false, true, OptionalDouble.empty());
    }

    /** Unicode code points. A null or empty string is zero. */
    public static int chars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.codePointCount(0, text.length());
    }

    /** {@code ceil(characters / 4)}, never negative. */
    public static int tokensFromChars(int characters) {
        if (characters <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, ((long) characters + 3L) / 4L);
    }

    private static int clamp(int value) {
        return Math.max(0, value);
    }

    private static int saturatingAdd(int left, int right) {
        long sum = (long) left + right;
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) sum;
    }
}
