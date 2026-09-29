package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Named prompts loaded from {@code prompts.yml}.
 * An unknown id falls back to the literal placeholder text.
 */
public final class PromptCatalog {

    static final Pattern ID = Pattern.compile("[a-z0-9_-]+");

    private static final Set<String> SETTINGS = Set.of(
            "prompt",
            "vars",
            "ttl",
            "fallback",
            "max-prompt-length",
            "model",
            "system-prompt",
            "temperature",
            "max-tokens"
    );

    private final Map<String, NamedPrompt> prompts;

    private PromptCatalog(Map<String, NamedPrompt> prompts) {
        this.prompts = Map.copyOf(prompts);
    }

    public static PromptCatalog empty() {
        return new PromptCatalog(Map.of());
    }

    public static Parsed parse(String yamlText) {
        String text = yamlText == null ? "" : yamlText;
        List<String> warnings = new ArrayList<>();
        warnings.addAll(duplicateIds(text));
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!warnings.isEmpty()) {
                message = String.join(" ", warnings) + " " + message;
            }
            return Parsed.invalid(message);
        }

        Map<String, NamedPrompt> loaded = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!ID.matcher(key).matches()) {
                warnings.add("Skipping prompt id '" + key + "': ids must match [a-z0-9_-].");
                continue;
            }
            Object raw = yaml.get(key);
            String template = readTemplate(key, raw, warnings);
            if (template == null) {
                continue;
            }
            Duration ttl = null;
            String fallback = null;
            Integer maxPromptLength = null;
            GenerationOverrides overrides = GenerationOverrides.none();
            Map<String, String> vars = Map.of();
            if (raw instanceof ConfigurationSection section) {
                vars = readVars(key, section, warnings);
                ttl = readTtl(key, section, warnings);
                fallback = readFallback(section);
                maxPromptLength = readMaxLength(key, section, warnings);
                overrides = readOverrides(key, section, warnings);
                warnUnknownSettings(key, section, warnings);
            }
            loaded.put(key, new NamedPrompt(key, template, vars, ttl, fallback, maxPromptLength, overrides));
        }
        warnCollisions(loaded, warnings);
        return new Parsed(new PromptCatalog(loaded), true, null, List.copyOf(warnings));
    }

    public Optional<NamedPrompt> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(prompts.get(id));
    }

    public List<String> ids() {
        List<String> ids = new ArrayList<>(prompts.keySet());
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    /**
     * @return the text to send for a configured pool or prewarm entry, or {@code null} when a player is required
     */
    public String staticText(String configured) {
        if (configured == null) {
            return "";
        }
        NamedPrompt named = prompts.get(configured);
        if (named == null) {
            return configured;
        }
        if (named.playerDependent()) {
            return null;
        }
        return named.render(value -> value);
    }

    public ResolvedPrompt resolve(String raw, PluginConfig config, UnaryOperator<String> placeholders) {
        String prompt = raw == null ? "" : raw;
        NamedPrompt named = prompts.get(prompt);
        if (named == null) {
            return ResolvedPrompt.literal(prompt, config);
        }
        return ResolvedPrompt.named(named, named.render(placeholders), config);
    }

    /**
     * Id-shaped values that are not defined here. Sentences are ignored so existing literal prompts stay quiet.
     */
    public List<String> unknownIdReferences(String source, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!ID.matcher(trimmed).matches() || prompts.containsKey(trimmed)) {
                continue;
            }
            warnings.add(source + " entry \"" + trimmed
                    + "\" looks like a prompt id but is not defined in prompts.yml. It will be used as a literal prompt.");
        }
        return List.copyOf(warnings);
    }

    private static String readTemplate(String id, Object raw, List<String> warnings) {
        if (raw instanceof String text) {
            return normalize(id, text, warnings);
        }
        if (raw instanceof List<?> lines) {
            return normalize(id, joinLines(lines), warnings);
        }
        if (raw instanceof ConfigurationSection section) {
            if (!section.contains("prompt")) {
                warnings.add("Skipping prompt '" + id + "': the prompt text is empty.");
                return null;
            }
            Object prompt = section.get("prompt");
            if (prompt instanceof String text) {
                return normalize(id, text, warnings);
            }
            if (prompt instanceof List<?> lines) {
                return normalize(id, joinLines(lines), warnings);
            }
            warnings.add("Skipping prompt '" + id + "': prompt must be a string or a list of lines.");
            return null;
        }
        warnings.add("Skipping prompt '" + id + "': prompt must be a string or a list of lines.");
        return null;
    }

    private static String normalize(String id, String text, List<String> warnings) {
        String stripped = stripTrailingNewlines(text == null ? "" : text);
        if (stripped.isBlank()) {
            warnings.add("Skipping prompt '" + id + "': the prompt text is empty.");
            return null;
        }
        return stripped;
    }

    private static String joinLines(List<?> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            Object item = lines.get(i);
            sb.append(item == null ? "" : String.valueOf(item));
        }
        return sb.toString();
    }

    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (ch != '\n' && ch != '\r') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    private static Map<String, String> readVars(String id, ConfigurationSection section, List<String> warnings) {
        if (!section.contains("vars")) {
            return Map.of();
        }
        ConfigurationSection varsSection = section.getConfigurationSection("vars");
        if (varsSection == null) {
            warnings.add("Prompt '" + id + "' vars must be a map. They were ignored.");
            return Map.of();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (String key : varsSection.getKeys(false)) {
            if (key == null || !key.matches("[A-Za-z0-9_-]+")) {
                warnings.add("Prompt '" + id + "' has an invalid var name '" + key + "'. It was ignored.");
                continue;
            }
            Object raw = varsSection.get(key);
            if (raw == null) {
                warnings.add("Prompt '" + id + "' var '" + key + "' is empty. It was ignored.");
                continue;
            }
            String value = String.valueOf(raw).trim();
            if (value.isEmpty()) {
                warnings.add("Prompt '" + id + "' var '" + key + "' is empty. It was ignored.");
                continue;
            }
            vars.put(key, value);
        }
        return vars;
    }

    private static Duration readTtl(String id, ConfigurationSection section, List<String> warnings) {
        if (!section.contains("ttl")) {
            return null;
        }
        Integer seconds = readInt(section.get("ttl"));
        if (seconds == null || seconds < 1) {
            warnings.add("Prompt '" + id + "' has an invalid ttl. The cache TTL from config.yml will be used.");
            return null;
        }
        return Duration.ofSeconds(seconds);
    }

    private static String readFallback(ConfigurationSection section) {
        if (!section.contains("fallback")) {
            return null;
        }
        Object raw = section.get("fallback");
        return raw == null ? "" : String.valueOf(raw);
    }

    private static Integer readMaxLength(String id, ConfigurationSection section, List<String> warnings) {
        if (!section.contains("max-prompt-length")) {
            return null;
        }
        Integer length = readInt(section.get("max-prompt-length"));
        if (length == null || length < 1) {
            warnings.add("Prompt '" + id + "' has an invalid max-prompt-length. It was ignored.");
            return null;
        }
        return length;
    }

    private static GenerationOverrides readOverrides(String id, ConfigurationSection section, List<String> warnings) {
        boolean systemSet = section.contains("system-prompt");
        String system = null;
        if (systemSet) {
            Object raw = section.get("system-prompt");
            system = raw == null ? "" : String.valueOf(raw);
        }
        boolean temperatureSet = section.contains("temperature");
        Double temperature = null;
        if (temperatureSet) {
            temperature = readDouble(section.get("temperature"));
            if (temperature == null) {
                warnings.add("Prompt '" + id + "' has an invalid temperature. It was ignored.");
                temperatureSet = false;
            }
        }
        boolean maxTokensSet = section.contains("max-tokens");
        Integer maxTokens = null;
        if (maxTokensSet) {
            Integer parsed = readInt(section.get("max-tokens"));
            if (parsed == null) {
                warnings.add("Prompt '" + id + "' has an invalid max-tokens. It was ignored.");
                maxTokensSet = false;
            } else {
                maxTokens = parsed;
            }
        }
        boolean modelSet = section.contains("model");
        String model = null;
        if (modelSet) {
            Object raw = section.get("model");
            model = raw == null ? "" : String.valueOf(raw).trim();
            if (model.isEmpty()) {
                warnings.add("Prompt '" + id + "' has an empty model. The model from config.yml will be used.");
                modelSet = false;
            }
        }
        return GenerationOverrides.of(
                systemSet, system, temperatureSet, temperature, maxTokensSet, maxTokens, modelSet, model);
    }

    private static void warnUnknownSettings(String id, ConfigurationSection section, List<String> warnings) {
        for (String key : section.getKeys(false)) {
            if (!SETTINGS.contains(key)) {
                warnings.add("Prompt '" + id + "' has unknown setting '" + key + "'. It was ignored.");
            }
        }
    }

    private static void warnCollisions(Map<String, NamedPrompt> loaded, List<String> warnings) {
        Map<String, List<String>> byText = new LinkedHashMap<>();
        for (NamedPrompt prompt : loaded.values()) {
            if (prompt.playerDependent()) {
                continue;
            }
            String text = prompt.render(value -> value);
            byText.computeIfAbsent(text, ignored -> new ArrayList<>()).add(prompt.id());
        }
        for (Map.Entry<String, List<String>> entry : byText.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add("Prompt ids " + entry.getValue()
                        + " resolve to the same text and will share a cache entry.");
            }
            NamedPrompt colliding = loaded.get(entry.getKey());
            if (colliding != null && !entry.getValue().contains(colliding.id())) {
                warnings.add("Prompt id '" + colliding.id() + "' collides with the text of prompt "
                        + entry.getValue() + ". Placeholders select an id before literal text.");
            }
        }
    }

    static List<String> duplicateIds(String yamlText) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : yamlText.split("\\R", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            char first = line.charAt(0);
            if (first == ' ' || first == '\t' || first == '#' || first == '-') {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            if (!ID.matcher(key).matches()) {
                continue;
            }
            counts.merge(key, 1, Integer::sum);
        }
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                warnings.add("prompts.yml contains the id '" + entry.getKey()
                        + "' more than once. YAML keeps one of them; rename or remove the duplicate.");
            }
        }
        return warnings;
    }

    private static Integer readInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double readDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record Parsed(PromptCatalog catalog, boolean valid, String error, List<String> warnings) {
        public Parsed {
            catalog = catalog == null ? PromptCatalog.empty() : catalog;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            if (error != null) {
                error = error.trim();
            }
        }

        public static Parsed invalid(String error) {
            String message = error == null || error.isBlank() ? "invalid YAML" : error.trim();
            return new Parsed(PromptCatalog.empty(), false, message, List.of());
        }
    }
}
