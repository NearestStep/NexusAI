package io.github.neareststep.nexusai.config;

import java.util.Objects;

/**
 * Optional per-call overrides. An unset field inherits the global config value.
 * A set field replaces it, including an explicit "omit" (null temperature / max tokens, blank system prompt).
 */
public final class GenerationOverrides {

    private static final GenerationOverrides NONE = new GenerationOverrides(
            false, null, false, null, false, null, false, null, false, null, false, null, null, null);

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
    private final boolean fallbackSet;
    private final String fallbackProvider;
    private final String fallbackModel;
    /**
     * Stable id for a length-trim notice. Not a generation parameter and not sent to the model.
     */
    private final String noticeId;

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
            String format,
            boolean fallbackSet,
            String fallbackProvider,
            String fallbackModel,
            String noticeId
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
        this.fallbackSet = fallbackSet;
        this.fallbackProvider = fallbackProvider;
        this.fallbackModel = fallbackModel;
        this.noticeId = noticeId;
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
                null,
                false,
                null,
                null,
                null
        );
    }

    public boolean isEmpty() {
        return !systemPromptSet && !temperatureSet && !maxTokensSet && !modelSet && !formatSet && !fallbackSet;
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
                format,
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                noticeId
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
                FormatPresets.normalize(format),
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                noticeId
        );
    }

    /** Sets temperature, including a negative value that omits the field on the wire. */
    public GenerationOverrides withTemperature(double value) {
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                true,
                value,
                maxTokensSet,
                maxTokens,
                modelSet,
                model,
                formatSet,
                format,
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                noticeId
        );
    }

    /** Sets max tokens, including {@code 0} or a negative value that omits the field on the wire. */
    public GenerationOverrides withMaxTokens(int value) {
        return new GenerationOverrides(
                systemPromptSet,
                systemPrompt,
                temperatureSet,
                temperature,
                true,
                value,
                modelSet,
                model,
                formatSet,
                format,
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                noticeId
        );
    }

    public GenerationOverrides withSystemPrompt(String system) {
        return new GenerationOverrides(
                true,
                system,
                temperatureSet,
                temperature,
                maxTokensSet,
                maxTokens,
                modelSet,
                model,
                formatSet,
                format,
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                noticeId
        );
    }

    /**
     * Per-call fallback model. A blank provider or model clears nothing and returns this instance.
     */
    public GenerationOverrides withFallbackModel(String provider, String modelName) {
        FallbackModel parsed = FallbackModel.of(provider, modelName);
        if (!parsed.configured()) {
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
                formatSet,
                format,
                true,
                parsed.provider(),
                parsed.model(),
                noticeId
        );
    }

    /**
     * Id logged when this call hits {@code max_tokens}. A prompt id, placeholder name,
     * pool name, or talk persona. Blank does not change this instance.
     */
    public GenerationOverrides withNoticeId(String id) {
        if (id == null || id.isBlank() || id.equals(noticeId)) {
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
                formatSet,
                format,
                fallbackSet,
                fallbackProvider,
                fallbackModel,
                id
        );
    }

    /**
     * @return the length-trim notice id, or {@code null} when this call has none
     */
    public String noticeId() {
        return noticeId;
    }

    /**
     * @return the per-call fallback model, or {@code null} when this call inherits "none"
     */
    public FallbackModel fallbackModel() {
        if (!fallbackSet) {
            return null;
        }
        FallbackModel parsed = FallbackModel.of(fallbackProvider, fallbackModel);
        return parsed.configured() ? parsed : null;
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
        if (onTop == null) {
            return this;
        }
        if (onTop.isEmpty()) {
            return onTop.noticeId == null ? this : withNoticeId(onTop.noticeId);
        }
        if (isEmpty()) {
            return noticeId == null || onTop.noticeId != null ? onTop : onTop.withNoticeId(noticeId);
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
                onTop.formatSet ? onTop.format : format,
                onTop.fallbackSet || fallbackSet,
                onTop.fallbackSet ? onTop.fallbackProvider : fallbackProvider,
                onTop.fallbackSet ? onTop.fallbackModel : fallbackModel,
                onTop.noticeId != null ? onTop.noticeId : noticeId
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
                && fallbackSet == that.fallbackSet
                && Objects.equals(systemPrompt, that.systemPrompt)
                && Objects.equals(temperature, that.temperature)
                && Objects.equals(maxTokens, that.maxTokens)
                && Objects.equals(model, that.model)
                && Objects.equals(format, that.format)
                && Objects.equals(fallbackProvider, that.fallbackProvider)
                && Objects.equals(fallbackModel, that.fallbackModel)
                && Objects.equals(noticeId, that.noticeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                systemPromptSet, systemPrompt, temperatureSet, temperature, maxTokensSet, maxTokens,
                modelSet, model, formatSet, format, fallbackSet, fallbackProvider, fallbackModel, noticeId);
    }
}
