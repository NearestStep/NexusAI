package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.ReasoningModels;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;

/**
 * Reservation size from section 5.4: prompt characters / 4, plus {@code max_tokens}.
 * When the call does not set {@code max_tokens}, the completion reserve is 1024.
 * A reasoning model reserves at least {@link ReasoningModels#TOKEN_FLOOR}.
 */
public final class QuotaEstimates {

    public static final long UNSET_COMPLETION = 1024L;

    private QuotaEstimates() {
    }

    public static long tokens(int promptChars, Integer maxTokens, boolean reasoning) {
        long prompt = ResponseUsage.tokensFromChars(promptChars);
        long completion = maxTokens == null || maxTokens <= 0 ? UNSET_COMPLETION : maxTokens;
        if (reasoning) {
            completion = Math.max(completion, ReasoningModels.TOKEN_FLOOR);
        }
        return prompt + completion;
    }

    /**
     * Characters of the user text plus the system text and format instruction this call will send.
     * The completion cap is the value the request will send. A null cap uses {@link #UNSET_COMPLETION}.
     */
    public static long forCall(PluginConfig config, String prompt, GenerationOverrides overrides, String model) {
        GenerationOverrides effective = overrides == null ? GenerationOverrides.none() : overrides;
        String system = effective.systemPrompt(config == null ? null : config.getSystemPrompt());
        String instruction = "";
        if (config != null) {
            var preset = config.presetFor(effective.formatOr(config.defaultFormatId()));
            if (preset != null && preset.instruction() != null) {
                instruction = preset.instruction();
            }
        }
        int chars = ResponseUsage.chars(prompt) + ResponseUsage.chars(system) + ResponseUsage.chars(instruction);
        Integer maxTokens = effective.maxTokens(config == null ? null : config.getMaxTokens());
        String resolved = model;
        if (resolved == null || resolved.isBlank()) {
            resolved = effective.model(config == null ? "" : config.getModel());
        }
        return tokens(chars, maxTokens, ReasoningModels.isReasoning(resolved));
    }
}
