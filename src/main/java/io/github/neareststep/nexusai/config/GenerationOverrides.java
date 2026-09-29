package io.github.neareststep.nexusai.config;

import java.util.Objects;

/**
 * Optional per-call overrides. An unset field inherits the global config value.
 * A set field replaces it, including an explicit "omit" (null temperature / max tokens, blank system prompt).
 */
public final class GenerationOverrides {

    private static final GenerationOverrides NONE = new GenerationOverrides(
            false, null, false, null, false, null, false, null, false, null);

    private final boolean systemPromptSet;
    private final String systemPrompt;
    private final boolean temperatureSet;
    private final Double temperature;
    private final boolean maxTokensSet;
    private final Integer maxTokens;
    private final boolean modelSet;
    private final String model;
    private final boolean formatSet;
    private final String format;

    private GenerationOverrides(
            boolean systemPromptSet,
            String systemPrompt,
            boolean temperatureSet,
            Double temperature,
            boolean maxTokensSet,
            Integer maxTokens,
            boolean modelSet,
            String model,
            boolean formatSet,
            String format
    ) {
        this.systemPromptSet = systemPromptSet;
        this.systemPrompt = systemPrompt;
        this.temperatureSet = temperatureSet;
        this.temperature = temperature;
        this.maxTokensSet = maxTokensSet;
        this.maxTokens = maxTokens;
        this.modelSet = modelSet;
        this.model = model;
        this.formatSet = formatSet;
        this.format = format;
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
        return of(systemPromptSet, systemPrompt, temperatureSet, temperature, maxTokensSet, maxTokens, false, null);
    }

    public static GenerationOverrides of(
            boolean systemPromptSet,
            String systemPrompt,
            boolean temperatureSet,
            Double temperature,
            boolean maxTokensSet,
            Integer maxTokens,
            boolean modelSet,
            String model
    ) {
        if (!systemPromptSet && !temperatureSet && !maxTokensSet && !modelSet) {
            return NONE;
        }
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                temperatureSet,
                temperature,
                maxTokensSet,
                maxTokens,
                modelSet,
                model,
                false,
                null
        );
    }

    public boolean isEmpty() {
        return !systemPromptSet && !temperatureSet && !maxTokensSet && !modelSet && !formatSet;
    }

    public boolean modelOverridden() {
        return modelSet && model != null && !model.isBlank();
    }

    public GenerationOverrides withModel(String model) {
        if (model == null || model.isBlank()) {
            return this;
        }
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                temperatureSet,
                temperature,
                maxTokensSet,
                maxTokens,
                true,
                model,
                formatSet,
                format
        );
    }

    public GenerationOverrides withFormat(String format) {
        if (format == null || format.isBlank()) {
            return this;
        }
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                temperatureSet,
                temperature,
                maxTokensSet,
                maxTokens,
                modelSet,
                model,
                true,
                FormatPresets.normalize(format)
        );
    }

    public String formatOr(String fallback) {
        if (!formatSet || format == null || format.isBlank()) {
            return fallback;
        }
        return format;
    }

    /**
     * Fields set on {@code onTop} replace this instance. Unset fields are kept.
     */
    public GenerationOverrides overlay(GenerationOverrides onTop) {
        if (onTop == null || onTop.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return onTop;
        }
        return new GenerationOverrides(
                onTop.systemPromptSet || systemPromptSet,
                onTop.systemPromptSet ? onTop.systemPrompt : systemPrompt,
                onTop.temperatureSet || temperatureSet,
                onTop.temperatureSet ? onTop.temperature : temperature,
                onTop.maxTokensSet || maxTokensSet,
                onTop.maxTokensSet ? onTop.maxTokens : maxTokens,
                onTop.modelSet || modelSet,
                onTop.modelSet ? onTop.model : model,
                onTop.formatSet || formatSet,
                onTop.formatSet ? onTop.format : format
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

    public String model(String global) {
        if (!modelSet || model == null || model.isBlank()) {
            return global;
        }
        return model;
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
                && modelSet == that.modelSet
                && formatSet == that.formatSet
                && Objects.equals(systemPrompt, that.systemPrompt)
                && Objects.equals(temperature, that.temperature)
                && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(model, that.model)
                && Objects.equals(format, that.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                systemPromptSet, systemPrompt, temperatureSet, temperature, maxTokensSet, maxTokens,
                modelSet, model, formatSet, format);
    }
}
