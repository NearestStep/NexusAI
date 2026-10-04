package io.github.neareststep.nexusai.ai;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Detects models that spend part of the token budget on hidden reasoning.
 */
public final class ReasoningModels {

    public static final int TOKEN_FLOOR = 2048;

    /**
     * o1 / o3 / o4 families. {@code gpt-4o} does not match: the character before {@code o} is a digit.
     */
    private static final Pattern O_SERIES = Pattern.compile(
            "(?:^|[/:_\\\\-])o[134](?:$|[-_.])",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * OpenAI gpt-5 family ({@code gpt-5}, {@code gpt-5-mini}, {@code gpt-5.1}, …).
     * The token after {@code gpt-5} must be a separator, so {@code gpt-50} and {@code gpt-4o} do not match.
     */
    private static final Pattern GPT5 = Pattern.compile(
            "(?:^|[/:_\\\\-])gpt-5(?:$|[-_.])",
            Pattern.CASE_INSENSITIVE
    );

    private ReasoningModels() {
    }

    public static boolean isReasoning(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        if (normalized.contains("gpt-oss")
                || normalized.contains("deepseek-r1")
                || normalized.contains("qwq")
                || normalized.contains("reasoner")) {
            return true;
        }
        return O_SERIES.matcher(normalized).find() || GPT5.matcher(normalized).find();
    }

    /**
     * OpenAI reasoning models reject {@code max_tokens} and {@code temperature}.
     * They expect {@code max_completion_tokens}. That is the o-series and the gpt-5 family.
     * Other reasoning names ({@code gpt-oss}, {@code deepseek-r1}, {@code qwq}, {@code reasoner})
     * keep {@code max_tokens}.
     */
    public static boolean usesCompletionTokenCap(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        return O_SERIES.matcher(model).find() || GPT5.matcher(model).find();
    }
}
