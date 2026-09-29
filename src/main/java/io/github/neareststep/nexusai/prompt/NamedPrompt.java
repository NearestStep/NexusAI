package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One named prompt from {@code prompts.yml}.
 * {@code {token}} markers are filled from {@link #vars()} before the model sees the text.
 */
public final class NamedPrompt {

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");

    private final String id;
    private final String template;
    private final Map<String, String> vars;
    private final Duration ttl;
    private final String fallback;
    private final Integer maxPromptLength;
    private final GenerationOverrides overrides;
    private final boolean playerDependent;
    private final Pattern resolvedPattern;

    public NamedPrompt(
            String id,
            String template,
            Map<String, String> vars,
            Duration ttl,
            String fallback,
            Integer maxPromptLength,
            GenerationOverrides overrides
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.template = Objects.requireNonNull(template, "template");
        this.vars = vars == null || vars.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(vars));
        this.ttl = ttl;
        this.fallback = fallback;
        this.maxPromptLength = maxPromptLength;
        this.overrides = overrides == null ? GenerationOverrides.none() : overrides;
        this.playerDependent = computePlayerDependent(this.template, this.vars);
        this.resolvedPattern = this.playerDependent ? compileResolved(this.template, this.vars) : null;
    }

    public String id() {
        return id;
    }

    public String template() {
        return template;
    }

    public Map<String, String> vars() {
        return vars;
    }

    /**
     * @return cache TTL override, or {@code null} to inherit {@code cache.ttl}
     */
    public Duration ttl() {
        return ttl;
    }

    /**
     * @return fallback override, or {@code null} to inherit the global fallback
     */
    public String fallback() {
        return fallback;
    }

    /**
     * @return cap on the resolved text, or {@code null} when the placeholder length limit does not apply
     */
    public Integer maxPromptLength() {
        return maxPromptLength;
    }

    public GenerationOverrides overrides() {
        return overrides;
    }

    /**
     * {@code true} when a var that appears in the template contains a PlaceholderAPI placeholder.
     * Those prompts must not share a cache or pool entry across different resolved values.
     */
    public boolean playerDependent() {
        return playerDependent;
    }

    public String render(UnaryOperator<String> placeholderResolver) {
        if (vars.isEmpty() || template.indexOf('{') < 0) {
            return template;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String value = entry.getValue();
            if (value.indexOf('%') >= 0) {
                String resolved = Objects.requireNonNull(placeholderResolver, "placeholderResolver").apply(value);
                value = resolved == null ? "" : resolved;
            }
            values.put(entry.getKey(), value);
        }
        return substitute(template, values);
    }

    /**
     * {@code true} when {@code text} could be this prompt after var substitution.
     * Used to reload player-specific pool rows.
     */
    public boolean matchesResolved(String text) {
        if (text == null) {
            return false;
        }
        if (!playerDependent) {
            return render(value -> value).equals(text);
        }
        return resolvedPattern != null && resolvedPattern.matcher(text).matches();
    }

    private static boolean computePlayerDependent(String template, Map<String, String> vars) {
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getValue().indexOf('%') >= 0 && template.contains('{' + entry.getKey() + '}')) {
                return true;
            }
        }
        return false;
    }

    static String substitute(String template, Map<String, String> values) {
        if (values.isEmpty() || template.indexOf('{') < 0) {
            return template;
        }
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort((left, right) -> Integer.compare(right.length(), left.length()));
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                pattern.append('|');
            }
            pattern.append(Pattern.quote("{" + keys.get(i) + "}"));
        }
        Matcher matcher = Pattern.compile(pattern.toString()).matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group();
            String key = token.substring(1, token.length() - 1);
            String value = values.getOrDefault(key, token);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Pattern compileResolved(String template, Map<String, String> vars) {
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = TOKEN.matcher(template);
        int last = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(last, matcher.start())));
            if (vars.containsKey(matcher.group(1))) {
                regex.append("(?s).*?");
            } else {
                regex.append(Pattern.quote(matcher.group()));
            }
            last = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(last)));
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
