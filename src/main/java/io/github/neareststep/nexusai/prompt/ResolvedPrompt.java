package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.pool.PoolKeys;

import java.time.Duration;
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
                GenerationOverrides.none().withFormat(format),
                format,
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
        return new ResolvedPrompt(
                true,
                prompt.id(),
                body,
                fallback,
                prompt.overrides().model(config.getModel()),
                prompt.ttl(),
                prompt.overrides().withFormat(format),
                format,
                usable
        );
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

    public String poolKey() {
        return PoolKeys.memory(formatId, text);
    }

    public boolean usable() {
        return usable;
    }
}
