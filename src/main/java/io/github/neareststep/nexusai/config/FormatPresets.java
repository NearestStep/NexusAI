package io.github.neareststep.nexusai.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in {@code format:} presets. {@code config.yml} may replace any field.
 */
public final class FormatPresets {

    public static final String SIMPLE = "simple";
    public static final List<String> IDS = List.of(
            "simple", "chat", "gui", "name", "hologram", "actionbar", "bossbar");

    private static final Map<String, FormatPreset> BUILTIN = build();

    private FormatPresets() {
    }

    public static FormatPreset builtin(String id) {
        FormatPreset preset = BUILTIN.get(normalize(id));
        return preset == null ? BUILTIN.get(SIMPLE) : preset;
    }

    public static boolean known(String id) {
        return id != null && BUILTIN.containsKey(normalize(id));
    }

    public static String normalize(String id) {
        if (id == null || id.isBlank()) {
            return SIMPLE;
        }
        return id.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * YAML fragment appended by migration when {@code formats:} is missing.
     */
    public static String defaultYaml() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("formats:\n");
        yaml.append("  # Optional preset used when a prompt does not set format:\n");
        yaml.append("  default: simple\n");
        for (String id : IDS) {
            FormatPreset preset = BUILTIN.get(id);
            yaml.append("  ").append(id).append(":\n");
            yaml.append("    instruction: ").append(YamlStrings.quote(preset.instruction())).append('\n');
            yaml.append("    max-lines: ").append(preset.maxLines()).append('\n');
            yaml.append("    max-chars: ").append(preset.maxChars()).append('\n');
            yaml.append("    max-chars-per-line: ").append(preset.maxCharsPerLine()).append('\n');
            yaml.append("    max-words: ").append(preset.maxWords()).append('\n');
            yaml.append("    max-sentences: ").append(preset.maxSentences()).append('\n');
            yaml.append("    strip-markdown: ").append(preset.stripMarkdown()).append('\n');
            yaml.append("    strip-trailing-punctuation: ").append(preset.stripTrailingPunctuation()).append('\n');
        }
        return yaml.toString();
    }

    private static Map<String, FormatPreset> build() {
        Map<String, FormatPreset> presets = new LinkedHashMap<>();
        presets.put("simple", new FormatPreset("simple", "", 0, 0, 0, 0, 0, false, false));
        presets.put("chat", new FormatPreset(
                "chat",
                "Reply in 1 to 3 sentences. Do not use markdown.",
                0, 0, 0, 0, 3, true, false));
        presets.put("gui", new FormatPreset(
                "gui",
                "Use at most 6 lines. Do not use markdown.",
                6, 0, 0, 0, 0, true, false));
        presets.put("name", new FormatPreset(
                "name",
                "Reply with 1 to 4 words and no trailing punctuation.",
                1, 0, 0, 4, 0, true, true));
        presets.put("hologram", new FormatPreset(
                "hologram",
                "Use at most 4 lines. Each line must be at most 40 characters. Separate lines with a newline.",
                4, 0, 40, 0, 0, true, false));
        presets.put("actionbar", new FormatPreset(
                "actionbar",
                "Reply with one sentence of at most 60 characters.",
                1, 60, 0, 0, 1, true, false));
        presets.put("bossbar", new FormatPreset(
                "bossbar",
                "Reply with one line of at most 80 characters.",
                1, 80, 0, 0, 0, true, false));
        return Map.copyOf(presets);
    }
}
