package io.github.neareststep.nexusai.config;

import java.util.Objects;

/**
 * Optional per-pool overrides. An unset field inherits the global config value.
 * A set field replaces it, including an explicit "omit" (null temperature / max tokens, blank system prompt).
 */
public final class GenerationOverrides {

    private static final GenerationOverrides NONE = new GenerationOverrides(false, null, false, null, false, null);

    private final boolean systemPromptSet;
    private final String systemPrompt;
    private final boolean temperatureSet;
    private final Double temperature;
    private final boolean maxTokensSet;
    private final Integer maxTokens;

    private GenerationOverrides(
            boolean systemPromptSet,
            String systemPrompt,
            boolean temperatureSet,
            Double temperature,
            boolean maxTokensSet,
            Integer maxTokens
    ) {
        this.systemPromptSet = systemPromptSet;
        this.systemPrompt = systemPrompt;
        this.temperatureSet = temperatureSet;
        this.temperature = temperature;
        this.maxTokensSet = maxTokensSet;
        this.maxTokens = maxTokens;
    }

    public static GenerationOverrides none() {
        return NONE;
    }

    public static GenerationOverrides of(
            boolean systemPromptSet,
            String systemPrompt,
            boolean temperatureSet,
            Double temperature,
            boolean maxTokensSet,
            Integer maxTokens
    ) {
        if (!systemPromptSet && !temperatureSet && !maxTokensSet) {
            return NONE;
        }
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                temperatureSet,
                temperature,
                maxTokensSet,
                maxTokens
        );
    }

    public String systemPrompt(String global) {
        if (!systemPromptSet) {
            return global;
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return null;
        }
        return systemPrompt;
    }

    public Double temperature(Double global) {
        if (!temperatureSet) {
            return global;
        }
        if (temperature == null || temperature < 0) {
            return null;
        }
        return temperature;
    }

    public Integer maxTokens(Integer global) {
        if (!maxTokensSet) {
            return global;
        }
        if (maxTokens == null || maxTokens <= 0) {
            return null;
        }
        return maxTokens;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenerationOverrides that)) {
            return false;
        }
        return systemPromptSet == that.systemPromptSet
                && temperatureSet == that.temperatureSet
                && maxTokensSet == that.maxTokensSet
                && Objects.equals(systemPrompt, that.systemPrompt)
                && Objects.equals(temperature, that.temperature)
                && Objects.equals(maxTokens, that.maxTokens);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPromptSet, systemPrompt, temperatureSet, temperature, maxTokensSet, maxTokens);
    }
}
