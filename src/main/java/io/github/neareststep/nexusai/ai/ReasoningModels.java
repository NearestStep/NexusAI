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
        return O_SERIES.matcher(normalized).find();
    }

    /**
     * OpenAI o-series rejects {@code max_tokens} and expects {@code max_completion_tokens}.
     */
    public static boolean usesCompletionTokenCap(String model) {
        return model != null && O_SERIES.matcher(model).find();
    }
}
