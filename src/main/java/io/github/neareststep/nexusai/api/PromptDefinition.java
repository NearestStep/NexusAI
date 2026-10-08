package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A prompt registered from plugin code. The id is {@code namespace:localId}
 * ({@link NexusAIApi#registerPrompt}).
 * <p>
 * {@code actions}, {@code dialogue}, and {@code context} are not part of this type.
 * Those are admin decisions. An entry with the same id in {@code prompts.yml} replaces
 * this definition and may add them. {@link #schema()} is the code contract and stays
 * when that file entry replaces everything else.
 * <p>
 * {@code vars} are default values. They are not PlaceholderAPI placeholders. A value
 * that contains {@code %} is rejected. Put player-specific placeholders in {@code prompts.yml}.
 */
public final class PromptDefinition {

    private static final Pattern VAR_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final String text;
    private final String format;
    private final String systemPrompt;
    private final Double temperature;
    private final Integer maxTokens;
    private final String model;
    private final Duration ttl;
    private final String fallback;
    private final Map<String, String> vars;
    private final List<String> knowledge;
    private final Object schema;

    private PromptDefinition(
            String text,
            String format,
            String systemPrompt,
            Double temperature,
            Integer maxTokens,
            String model,
            Duration ttl,
            String fallback,
            Map<String, String> vars,
            List<String> knowledge,
            Object schema
    ) {
        this.text = text;
        this.format = format;
        this.systemPrompt = systemPrompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.model = model;
        this.ttl = ttl;
        this.fallback = fallback;
        this.vars = vars;
        this.knowledge = knowledge;
        this.schema = schema;
    }

    public static Builder builder(String text) {
        return new Builder(text);
    }

    public String text() {
        return text;
    }

    /** Format id, or {@code null} to inherit {@code formats.default}. */
    public String format() {
        return format;
    }

    /** System prompt, or {@code null} when this definition does not set one. */
    public String systemPrompt() {
        return systemPrompt;
    }

    /** Temperature, or {@code null} when this definition does not set one. */
    public Double temperature() {
        return temperature;
    }

    /** Max tokens, or {@code null} when this definition does not set one. */
    public Integer maxTokens() {
        return maxTokens;
    }

    /** Model name on the queue rows, or {@code null} to inherit {@code api.model}. */
    public String model() {
        return model;
    }

    /** Cache TTL, or {@code null} to inherit {@code cache.ttl}. */
    public Duration ttl() {
        return ttl;
    }

    /** Fallback text, or {@code null} to inherit the global fallback. */
    public String fallback() {
        return fallback;
    }

    public Map<String, String> vars() {
        return vars;
    }

    public List<String> knowledge() {
        return knowledge;
    }

    /**
     * Code schema. {@code prompts.yml} does not replace this value.
     * {@code generateJson} will read it. Until that method exists the object is stored and is not sent.
     */
    @ApiStatus.Internal
    public Object schema() {
        return schema;
    }

    public static final class Builder {
        private String text;
        private String format;
        private boolean systemPromptSet;
        private String systemPrompt;
        private Double temperature;
        private Integer maxTokens;
        private String model;
        private Duration ttl;
        private boolean fallbackSet;
        private String fallback;
        private Map<String, String> vars = Map.of();
        private List<String> knowledge = List.of();
        private Object schema;

        private Builder(String text) {
            this.text = text;
        }

        public Builder format(String formatId) {
            if (formatId == null || formatId.isBlank()) {
                throw new IllegalArgumentException("format is empty");
            }
            this.format = FormatPresets.normalize(formatId);
            return this;
        }

        public Builder systemPrompt(String text) {
            if (text == null) {
                throw new IllegalArgumentException("systemPrompt is required");
            }
            this.systemPromptSet = true;
            this.systemPrompt = text;
            return this;
        }

        public Builder temperature(double temperature) {
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > GenerationRequest.MAX_TEMPERATURE) {
                throw new IllegalArgumentException("temperature must be from 0 to " + GenerationRequest.MAX_TEMPERATURE);
            }
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            if (maxTokens < 1 || maxTokens > GenerationRequest.MAX_MAX_TOKENS) {
                throw new IllegalArgumentException(
                        "maxTokens must be from 1 to " + GenerationRequest.MAX_MAX_TOKENS);
            }
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder model(String model) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model is empty");
            }
            this.model = model.trim();
            return this;
        }

        public Builder ttl(Duration ttl) {
            if (ttl == null || ttl.compareTo(GenerationRequest.MIN_TTL) < 0) {
                throw new IllegalArgumentException("ttl must be at least 1 second");
            }
            this.ttl = ttl;
            return this;
        }

        public Builder fallback(String text) {
            if (text == null) {
                throw new IllegalArgumentException("fallback is required");
            }
            this.fallbackSet = true;
            this.fallback = text;
            return this;
        }

        /** Default var values. A value that contains {@code %} is rejected. */
        public Builder vars(Map<String, String> defaults) {
            if (defaults == null) {
                throw new IllegalArgumentException("vars are required");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                String name = entry.getKey();
                if (name == null || !VAR_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("var name must match [A-Za-z0-9_-]+");
                }
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("var '" + name + "' is empty");
                }
                if (value.indexOf('%') >= 0) {
                    throw new IllegalArgumentException(
                            "var '" + name + "' cannot contain a PlaceholderAPI placeholder;"
                                    + " put player-specific vars in prompts.yml");
                }
                copy.put(name, value);
            }
            this.vars = copy;
            return this;
        }

        public Builder knowledge(List<String> fileNames) {
            if (fileNames == null) {
                throw new IllegalArgumentException("knowledge is required");
            }
            List<String> names = new ArrayList<>();
            for (String fileName : fileNames) {
                String name = fileName == null ? "" : fileName.trim();
                if (!KnowledgeBase.validName(name)) {
                    throw new IllegalArgumentException("knowledge file name is invalid");
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            this.knowledge = names;
            return this;
        }

        /**
         * Stores the code schema. An admin override in {@code prompts.yml} does not replace it.
         */
        @ApiStatus.Internal
        public Builder schema(Object schema) {
            this.schema = schema;
            return this;
        }

        public PromptDefinition build() {
            String body = stripTrailingNewlines(text == null ? "" : text);
            if (body.isBlank()) {
                throw new IllegalArgumentException("prompt text is empty");
            }
            Map<String, String> varCopy = vars.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            List<String> knowledgeCopy = knowledge.isEmpty() ? List.of() : List.copyOf(knowledge);
            return new PromptDefinition(
                    body,
                    format,
                    systemPromptSet ? systemPrompt : null,
                    temperature,
                    maxTokens,
                    model,
                    ttl,
                    fallbackSet ? fallback : null,
                    varCopy,
                    knowledgeCopy,
                    schema);
        }

        private static String stripTrailingNewlines(String value) {
            int end = value.length();
            while (end > 0) {
                char ch = value.charAt(end - 1);
                if (ch != '\n' && ch != '\r') {
                    break;
                }
                end--;
            }
            return value.substring(0, end);
        }
    }
}
