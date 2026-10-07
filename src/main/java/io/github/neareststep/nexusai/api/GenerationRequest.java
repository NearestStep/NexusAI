package io.github.neareststep.nexusai.api;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One {@link NexusAIApi#generate} call.
 * <p>
 * {@link Builder#build()} checks the shape of the request on the calling thread and throws
 * {@link IllegalArgumentException} when the shape is wrong. Limits that depend on
 * {@code plugin-api.*} are checked later, inside {@code generate}, because {@code /nai reload}
 * can change them after {@code build()}.
 */
public final class GenerationRequest {

    static final Pattern VAR_NAME = Pattern.compile("[a-z0-9_]{1,32}");
    static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    static final int MAX_VARS = 32;
    static final double MAX_TEMPERATURE = 2.0d;
    static final int MAX_MAX_TOKENS = 32_768;
    static final Duration MIN_TTL = Duration.ofSeconds(1);
    static final Duration MAX_TTL = Duration.ofHours(24);

    private final String promptId;
    private final String template;
    private final Map<String, String> vars;
    private final UUID playerId;
    private final Player player;
    private final CacheMode cacheMode;
    private final boolean maxTokensSet;
    private final int maxTokens;
    private final boolean temperatureSet;
    private final double temperature;
    private final String model;
    private final String format;
    private final boolean systemPromptSet;
    private final String systemPrompt;
    private final String fallback;
    private final List<String> knowledge;
    private final String knowledgeQuery;
    private final Duration ttl;
    private final String label;

    private GenerationRequest(
            String promptId,
            String template,
            Map<String, String> vars,
            UUID playerId,
            Player player,
            CacheMode cacheMode,
            boolean maxTokensSet,
            int maxTokens,
            boolean temperatureSet,
            double temperature,
            String model,
            String format,
            boolean systemPromptSet,
            String systemPrompt,
            String fallback,
            List<String> knowledge,
            String knowledgeQuery,
            Duration ttl,
            String label
    ) {
        this.promptId = promptId;
        this.template = template;
        this.vars = vars;
        this.playerId = playerId;
        this.player = player;
        this.cacheMode = cacheMode;
        this.maxTokensSet = maxTokensSet;
        this.maxTokens = maxTokens;
        this.temperatureSet = temperatureSet;
        this.temperature = temperature;
        this.model = model;
        this.format = format;
        this.systemPromptSet = systemPromptSet;
        this.systemPrompt = systemPrompt;
        this.fallback = fallback;
        this.knowledge = knowledge;
        this.knowledgeQuery = knowledgeQuery;
        this.ttl = ttl;
        this.label = label;
    }

    /** A named prompt from {@code prompts.yml}. */
    public static Builder prompt(String promptId) {
        return new Builder(false, promptId);
    }

    /**
     * An inline template. The text is trusted the same way an admin prompt is trusted.
     * Variable values are not trusted: see {@link Builder#var}.
     */
    public static Builder template(String text) {
        return new Builder(true, text);
    }

    public Optional<String> promptId() {
        return promptId == null ? Optional.empty() : Optional.of(promptId);
    }

    public Optional<String> template() {
        return template == null ? Optional.empty() : Optional.of(template);
    }

    /** Unmodifiable. A missing variable value was stored as {@code ""}. */
    public Map<String, String> vars() {
        return vars;
    }

    /**
     * Player id for limits, or empty when the request named no player.
     * {@link Builder#player} reads the id on the calling thread.
     */
    public Optional<UUID> playerId() {
        return playerId == null ? Optional.empty() : Optional.of(playerId);
    }

    public CacheMode cacheMode() {
        return cacheMode;
    }

    /**
     * Set value, including {@code 0} or a negative value, which omits {@code max_tokens} on the wire.
     * Empty when {@link Builder#maxTokens} was not called.
     */
    public OptionalInt maxTokens() {
        return maxTokensSet ? OptionalInt.of(maxTokens) : OptionalInt.empty();
    }

    /**
     * Set value, including a negative value, which omits {@code temperature} on the wire.
     * Empty when {@link Builder#temperature} was not called.
     */
    public OptionalDouble temperature() {
        return temperatureSet ? OptionalDouble.of(temperature) : OptionalDouble.empty();
    }

    public Optional<String> model() {
        return model == null ? Optional.empty() : Optional.of(model);
    }

    public Optional<String> format() {
        return format == null ? Optional.empty() : Optional.of(format);
    }

    /** Set text, including blank, which omits the system prompt on the wire. */
    public Optional<String> systemPrompt() {
        return systemPromptSet ? Optional.of(systemPrompt) : Optional.empty();
    }

    public Optional<String> fallback() {
        return fallback == null ? Optional.empty() : Optional.of(fallback);
    }

    /** Knowledge file names without an extension. Empty inherits the prompt list, or none for a template. */
    public List<String> knowledge() {
        return knowledge;
    }

    /** Stored for a later keyword selector. This version does not read it. */
    public Optional<String> knowledgeQuery() {
        return knowledgeQuery == null ? Optional.empty() : Optional.of(knowledgeQuery);
    }

    public Optional<Duration> ttl() {
        return ttl == null ? Optional.empty() : Optional.of(ttl);
    }

    /** Correlation label, or {@code ""} when none was set. */
    public String label() {
        return label;
    }

    boolean maxTokensSpecified() {
        return maxTokensSet;
    }

    int maxTokensRaw() {
        return maxTokens;
    }

    boolean temperatureSpecified() {
        return temperatureSet;
    }

    double temperatureRaw() {
        return temperature;
    }

    boolean systemPromptSpecified() {
        return systemPromptSet;
    }

    String systemPromptRaw() {
        return systemPrompt;
    }

    Player playerEntity() {
        return player;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GenerationRequest that)) {
            return false;
        }
        return maxTokensSet == that.maxTokensSet
                && maxTokens == that.maxTokens
                && temperatureSet == that.temperatureSet
                && Double.doubleToLongBits(temperature) == Double.doubleToLongBits(that.temperature)
                && systemPromptSet == that.systemPromptSet
                && (player == null) == (that.player == null)
                && Objects.equals(promptId, that.promptId)
                && Objects.equals(template, that.template)
                && vars.equals(that.vars)
                && Objects.equals(playerId, that.playerId)
                && cacheMode == that.cacheMode
                && Objects.equals(model, that.model)
                && Objects.equals(format, that.format)
                && Objects.equals(systemPrompt, that.systemPrompt)
                && Objects.equals(fallback, that.fallback)
                && knowledge.equals(that.knowledge)
                && Objects.equals(knowledgeQuery, that.knowledgeQuery)
                && Objects.equals(ttl, that.ttl)
                && label.equals(that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                promptId, template, vars, playerId, player != null, cacheMode,
                maxTokensSet, maxTokens, temperatureSet, temperature, model, format,
                systemPromptSet, systemPrompt, fallback, knowledge, knowledgeQuery, ttl, label);
    }

    @Override
    public String toString() {
        return "GenerationRequest{promptId=" + (promptId == null ? "" : promptId)
                + ", templateChars=" + (template == null ? 0 : template.codePointCount(0, template.length()))
                + ", vars=" + vars.size()
                + ", playerId=" + (playerId == null ? "" : playerId)
                + ", playerEntity=" + (player != null)
                + ", cacheMode=" + cacheMode
                + ", label=" + label
                + "}";
    }

    /**
     * Fluent request. {@link #build()} is the only method that returns a request.
     */
    public static final class Builder {

        private final boolean templateMode;
        private final String source;
        private final Map<String, String> vars = new LinkedHashMap<>();
        private UUID playerId;
        private Player player;
        private CacheMode cacheMode = CacheMode.CACHED;
        private boolean maxTokensSet;
        private int maxTokens;
        private boolean temperatureSet;
        private double temperature;
        private String model;
        private String format;
        private boolean systemPromptSet;
        private String systemPrompt;
        private String fallback;
        private List<String> knowledge = List.of();
        private String knowledgeQuery;
        private Duration ttl;
        private String label = "";

        private Builder(boolean templateMode, String source) {
            this.templateMode = templateMode;
            this.source = source;
        }

        /**
         * One variable. The value is untrusted player or plugin data: NexusAI sanitizes it and
         * wraps it in player-input markers. PlaceholderAPI inside the value is not expanded.
         * A null value is stored as {@code ""}. The name must match {@code [a-z0-9_]{1,32}}.
         */
        public Builder var(String name, String value) {
            if (name == null) {
                throw new IllegalArgumentException("variable name must match [a-z0-9_]{1,32}");
            }
            vars.put(name, value == null ? "" : value);
            return this;
        }

        /**
         * Replaces every variable set so far. A null value is stored as {@code ""}.
         * Later {@link #var} calls add to this map.
         */
        public Builder vars(Map<String, String> values) {
            if (values == null) {
                throw new IllegalArgumentException("vars are required");
            }
            vars.clear();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                var(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * Reads {@link Player#getUniqueId()} on this thread, then keeps the entity so a later
         * call can read world, biome, placeholders, and context on the player's region thread.
         * The last of {@code player} and {@link #playerId} wins.
         */
        public Builder player(Player online) {
            if (online == null) {
                throw new IllegalArgumentException("player is required");
            }
            UUID id = online.getUniqueId();
            if (id == null) {
                throw new IllegalArgumentException("player is required");
            }
            this.player = online;
            this.playerId = id;
            return this;
        }

        /**
         * Limits and quotas only. NexusAI does not read this player's world or placeholders.
         * The last of {@link #player} and {@code playerId} wins. Passing an id clears a previously
         * set entity.
         */
        public Builder playerId(UUID id) {
            if (id == null) {
                throw new IllegalArgumentException("playerId is required");
            }
            this.playerId = id;
            this.player = null;
            return this;
        }

        public Builder cacheMode(CacheMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("cacheMode is required");
            }
            this.cacheMode = mode;
            return this;
        }

        /** {@code 0} or a negative value omits {@code max_tokens}. The maximum is 32768. */
        public Builder maxTokens(int tokens) {
            this.maxTokensSet = true;
            this.maxTokens = tokens;
            return this;
        }

        /** A negative value omits {@code temperature}. The maximum is 2. */
        public Builder temperature(double value) {
            this.temperatureSet = true;
            this.temperature = value;
            return this;
        }

        /** Model name, matched against the model queue the same way a prompt {@code model:} is. */
        public Builder model(String modelName) {
            if (modelName == null) {
                throw new IllegalArgumentException("model is required");
            }
            if (modelName.isBlank()) {
                throw new IllegalArgumentException("model is empty");
            }
            this.model = modelName;
            return this;
        }

        /** A preset from {@code formats:}. An unknown id uses {@code formats.default} and is not an error. */
        public Builder format(String formatId) {
            if (formatId == null) {
                throw new IllegalArgumentException("format is required");
            }
            if (formatId.isBlank()) {
                throw new IllegalArgumentException("format is empty");
            }
            this.format = formatId;
            return this;
        }

        /**
         * Trusted admin text, like {@code system-prompt} on a prompt. Blank omits the field.
         * Variable values are not accepted here.
         */
        public Builder systemPrompt(String text) {
            if (text == null) {
                throw new IllegalArgumentException("systemPrompt is required");
            }
            this.systemPromptSet = true;
            this.systemPrompt = text;
            return this;
        }

        /** Used when the model does not answer. Blank is a blank fallback, not the config fallback. */
        public Builder fallback(String text) {
            if (text == null) {
                throw new IllegalArgumentException("fallback is required");
            }
            this.fallback = text;
            return this;
        }

        /**
         * Knowledge file names without an extension. A non-empty list replaces the prompt's list.
         * An empty list inherits the prompt list, or attaches nothing for a template.
         */
        public Builder knowledge(List<String> fileNames) {
            if (fileNames == null) {
                throw new IllegalArgumentException("knowledge is required");
            }
            for (String name : fileNames) {
                if (name == null) {
                    throw new IllegalArgumentException("knowledge name is required");
                }
            }
            this.knowledge = List.copyOf(fileNames);
            return this;
        }

        /** Keywords for a later selector. This version stores the text and does not use it. */
        public Builder knowledgeQuery(String text) {
            if (text == null) {
                throw new IllegalArgumentException("knowledgeQuery is required");
            }
            this.knowledgeQuery = text;
            return this;
        }

        /** Cache TTL from 1 second through 24 hours. */
        public Builder ttl(Duration duration) {
            if (duration == null) {
                throw new IllegalArgumentException("ttl is required");
            }
            this.ttl = duration;
            return this;
        }

        /** {@code [A-Za-z0-9_.:-]{1,64}}. Copied onto the result and onto later events. */
        public Builder label(String value) {
            if (value == null) {
                throw new IllegalArgumentException("label is required");
            }
            if (!LABEL.matcher(value).matches()) {
                throw new IllegalArgumentException("label must match [A-Za-z0-9_.:-]{1,64}");
            }
            this.label = value;
            return this;
        }

        /** Checks the shape of this request. A violation throws {@link IllegalArgumentException} now. */
        public GenerationRequest build() {
            if (templateMode) {
                if (source == null || source.isBlank()) {
                    throw new IllegalArgumentException("template is empty");
                }
            } else if (source == null || source.isBlank()) {
                throw new IllegalArgumentException("promptId is empty");
            }
            if (vars.size() > MAX_VARS) {
                throw new IllegalArgumentException("at most 32 variables");
            }
            for (String name : vars.keySet()) {
                if (name == null || !VAR_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("variable name must match [a-z0-9_]{1,32}");
                }
            }
            if (!label.isEmpty() && !LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("label must match [A-Za-z0-9_.:-]{1,64}");
            }
            if (ttl != null && (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0)) {
                throw new IllegalArgumentException("ttl must be from 1 second to 24 hours");
            }
            if (temperatureSet && (!Double.isFinite(temperature) || temperature > MAX_TEMPERATURE)) {
                throw new IllegalArgumentException("temperature must be at most 2");
            }
            if (maxTokensSet && maxTokens > MAX_MAX_TOKENS) {
                throw new IllegalArgumentException("maxTokens must be at most 32768");
            }
            Map<String, String> copy = vars.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(vars));
            return new GenerationRequest(
                    templateMode ? null : source,
                    templateMode ? source : null,
                    copy,
                    playerId,
                    player,
                    cacheMode,
                    maxTokensSet,
                    maxTokens,
                    temperatureSet,
                    temperature,
                    model,
                    format,
                    systemPromptSet,
                    systemPromptSet ? systemPrompt : null,
                    fallback,
                    knowledge,
                    knowledgeQuery,
                    ttl,
                    label
            );
        }
    }
}
