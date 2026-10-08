package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.pool.PoolKeys;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A placeholder argument after named-prompt lookup.
 * Literal prompts keep the historical length check. Named prompts use the file text.
 */
public final class ResolvedPrompt {

    private final boolean named;
    private final String id;
    private final String text;
    private final String fallback;
    private final String model;
    private final Duration ttl;
    private final GenerationOverrides overrides;
    private final String formatId;
    private final List<String> knowledge;
    private final KnowledgeSelect knowledgeSelect;
    private final List<String> knowledgeKeywords;
    private final boolean usable;

    private ResolvedPrompt(
            boolean named,
            String id,
            String text,
            String fallback,
            String model,
            Duration ttl,
            GenerationOverrides overrides,
            String formatId,
            List<String> knowledge,
            KnowledgeSelect knowledgeSelect,
            List<String> knowledgeKeywords,
            boolean usable
    ) {
        this.named = named;
        this.id = id;
        this.text = text;
        this.fallback = fallback;
        this.model = model;
        this.ttl = ttl;
        this.overrides = overrides;
        this.formatId = formatId;
        this.knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        this.knowledgeSelect = knowledgeSelect;
        this.knowledgeKeywords = knowledgeKeywords == null || knowledgeKeywords.isEmpty()
                ? List.of()
                : List.copyOf(knowledgeKeywords);
        this.usable = usable;
    }

    public static ResolvedPrompt literal(String text, PluginConfig config) {
        Objects.requireNonNull(config, "config");
        String prompt = text == null ? "" : text;
        boolean usable = !prompt.isEmpty() && prompt.length() <= config.getMaxPromptLength();
        String format = config.defaultFormatId();
        return new ResolvedPrompt(
                false,
                null,
                prompt,
                config.getFallback(),
                config.getModel(),
                null,
                withFallback(GenerationOverrides.none().withFormat(format), null, config),
                format,
                List.of(),
                null,
                List.of(),
                usable
        );
    }

    public static ResolvedPrompt named(NamedPrompt prompt, String text, PluginConfig config) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(config, "config");
        String body = text == null ? "" : text;
        String fallback = prompt.fallback() != null ? prompt.fallback() : config.getFallback();
        Integer max = prompt.maxPromptLength();
        boolean usable = !body.isBlank() && (max == null || body.length() <= max);
        String format = prompt.format() == null ? config.defaultFormatId() : config.normalizeFormat(prompt.format());
        GenerationOverrides overrides = withFallback(prompt.overrides().withFormat(format), prompt.fallbackModel(), config);
        return new ResolvedPrompt(
                true,
                prompt.id(),
                body,
                fallback,
                overrides.model(config.getModel()),
                prompt.ttl(),
                overrides,
                format,
                prompt.knowledge(),
                prompt.knowledgeSelect(),
                prompt.knowledgeKeywords(),
                usable
        );
    }

    private static GenerationOverrides withFallback(GenerationOverrides overrides, FallbackModel promptModel, PluginConfig config) {
        GenerationOverrides base = overrides == null ? GenerationOverrides.none() : overrides;
        if (base.fallbackModel() != null) {
            return base;
        }
        FallbackModel chosen = promptModel != null && promptModel.configured() ? promptModel : config.fallbackModel();
        if (chosen == null || !chosen.configured()) {
            return base;
        }
        return base.withFallbackModel(chosen.provider(), chosen.model());
    }

    public boolean named() {
        return named;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public String fallback() {
        return fallback;
    }

    public String model() {
        return model;
    }

    /**
     * @return per-prompt cache TTL, or {@code null} to inherit {@code cache.ttl}
     */
    public Duration ttl() {
        return ttl;
    }

    public GenerationOverrides overrides() {
        return overrides;
    }

    public String formatId() {
        return formatId;
    }

    public List<String> knowledge() {
        return knowledge;
    }

    /**
     * @return prompt {@code knowledge-select}, or {@code null} when this prompt inherits the global mode
     */
    public KnowledgeSelect knowledgeSelect() {
        return knowledgeSelect;
    }

    public List<String> knowledgeKeywords() {
        return knowledgeKeywords;
    }

    public String poolKey() {
        return PoolKeys.memory(formatId, text);
    }

    public boolean usable() {
        return usable;
    }
}
