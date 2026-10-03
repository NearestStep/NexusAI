package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.FileBackup;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Copies prompt definitions from {@code plugins/NexusAI/import/<file>} into {@code prompts.yml}.
 * Ids and fields are validated by {@link PromptCatalog}. An existing id is left in place
 * unless overwrite is requested. {@code prompts.yml} is copied to {@code .bak} before it changes.
 */
public final class PromptImporter {

    private PromptImporter() {
    }

    public record Report(
            boolean success,
            boolean changed,
            String error,
            String fileName,
            List<String> added,
            List<String> skipped,
            List<String> conflicting,
            Path backup,
            List<String> warnings
    ) {
        public Report {
            added = added == null ? List.of() : List.copyOf(added);
            skipped = skipped == null ? List.of() : List.copyOf(skipped);
            conflicting = conflicting == null ? List.of() : List.copyOf(conflicting);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static Report failed(String error) {
            return new Report(false, false, error, "", List.of(), List.of(), List.of(), null, List.of());
        }
    }

    public static Report importFile(Path dataFolder, String requested, boolean overwrite) {
        if (dataFolder == null) {
            return Report.failed("data folder is missing");
        }
        Path importDir = dataFolder.resolve("import");
        Path resolved;
        try {
            resolved = resolveInside(importDir, requested);
        } catch (IllegalArgumentException e) {
            return Report.failed(e.getMessage());
        }
        if (!Files.isRegularFile(resolved)) {
            return Report.failed("import file was not found: " + safeName(requested));
        }
        String importText;
        String existingText;
        Path promptsFile = dataFolder.resolve("prompts.yml");
        try {
            importText = Files.readString(resolved, StandardCharsets.UTF_8);
            existingText = Files.isRegularFile(promptsFile)
                    ? Files.readString(promptsFile, StandardCharsets.UTF_8)
                    : "config-version: 1\n";
        } catch (IOException e) {
            return Report.failed("could not read the import file");
        }

        PromptCatalog.Parsed incoming = PromptCatalog.parse(importText);
        if (!incoming.valid()) {
            return new Report(false, false, incoming.error(), resolved.getFileName().toString(),
                    List.of(), List.of(), List.of(), null, incoming.warnings());
        }
        PromptCatalog.Parsed current = PromptCatalog.parse(existingText);
        if (!current.valid()) {
            return Report.failed("prompts.yml has a syntax error and was left unchanged. " + current.error());
        }

        Set<String> existingIds = new LinkedHashSet<>(current.catalog().ids());
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> conflicting = new ArrayList<>();
        List<String> writeIds = new ArrayList<>();
        for (String id : topLevelIds(importText)) {
            if (incoming.catalog().find(id).isEmpty()) {
                skipped.add(id);
            }
        }
        for (String id : incoming.catalog().ids()) {
            if (existingIds.contains(id)) {
                conflicting.add(id);
                if (overwrite) {
                    writeIds.add(id);
                } else {
                    skipped.add(id);
                }
            } else {
                added.add(id);
                writeIds.add(id);
            }
        }
        String fileName = resolved.getFileName().toString();
        if (writeIds.isEmpty()) {
            return new Report(true, false, null, fileName, added, skipped, conflicting, null, incoming.warnings());
        }

        String updated = splice(existingText, importText, writeIds);
        Path backup = null;
        try {
            if (Files.isRegularFile(promptsFile)) {
                backup = FileBackup.backup(promptsFile);
            } else {
                Files.createDirectories(dataFolder);
            }
            AtomicFiles.preserving(promptsFile, () -> Files.writeString(promptsFile, updated, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Report.failed("could not update prompts.yml");
        }
        return new Report(true, true, null, fileName, added, skipped, conflicting, backup, incoming.warnings());
    }

    /**
     * Resolves {@code requested} inside {@code importDir}. Absolute paths and {@code ..} are rejected.
     */
    public static Path resolveInside(Path importDir, String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("import file name is empty");
        }
        String name = requested.trim();
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("import path is not inside the import folder");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) {
            throw new IllegalArgumentException("import file must end with .yml or .yaml");
        }
        Path raw = Path.of(name);
        if (raw.isAbsolute()) {
            throw new IllegalArgumentException("import path is not inside the import folder");
        }
        for (Path part : raw) {
            String segment = part.toString();
            if (segment.equals("..") || segment.equals(".") || segment.isBlank()) {
                throw new IllegalArgumentException("import path is not inside the import folder");
            }
        }
        Path root = importDir.toAbsolutePath().normalize();
        Path resolved = root.resolve(raw).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("import path is not inside the import folder");
        }
        try {
            if (Files.exists(resolved)) {
                Path realRoot = root.toRealPath();
                Path realFile = resolved.toRealPath();
                if (!realFile.startsWith(realRoot)) {
                    throw new IllegalArgumentException("import path is not inside the import folder");
                }
                return realFile;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("import path is not inside the import folder");
        }
        return resolved;
    }

    static List<String> topLevelIds(String yamlText) {
        List<String> ids = new ArrayList<>();
        if (yamlText == null || yamlText.isBlank()) {
            return ids;
        }
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
            if (PromptCatalog.ID.matcher(key).matches() && !"config-version".equals(key)) {
                ids.add(key);
            }
        }
        return ids;
    }

    /**
     * Inserts or replaces top-level prompt blocks without rewriting the rest of {@code prompts.yml}.
     */
    static String splice(String existingYaml, String importYaml, List<String> ids) {
        String base = existingYaml == null ? "" : existingYaml;
        String source = importYaml == null ? "" : importYaml;
        for (String id : ids) {
            String block = extractBlock(source, id);
            if (block == null) {
                block = serialize(source, id);
            }
            if (block == null || block.isBlank()) {
                continue;
            }
            if (!block.endsWith("\n")) {
                block = block + "\n";
            }
            if (containsTopLevel(base, id)) {
                base = replaceBlock(base, id, block);
            } else {
                if (!base.isEmpty() && !base.endsWith("\n")) {
                    base = base + "\n";
                }
                if (!base.isEmpty() && !base.endsWith("\n\n")) {
                    base = base + "\n";
                }
                base = base + block;
            }
        }
        return base;
    }

    private static boolean containsTopLevel(String yaml, String id) {
        return extractBlock(yaml, id) != null;
    }

    private static String extractBlock(String yaml, String id) {
        String[] lines = yaml.split("\\R", -1);
        String prefix = id + ":";
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.equals(prefix) || line.startsWith(prefix)) {
                if (isTopLevelKey(line) && keyOf(line).equals(id)) {
                    start = i;
                    break;
                }
            }
        }
        if (start < 0) {
            return null;
        }
        int end = lines.length;
        for (int i = start + 1; i < lines.length; i++) {
            if (isTopLevelKey(lines[i])) {
                end = i;
                break;
            }
        }
        StringBuilder block = new StringBuilder();
        String newline = yaml.contains("\r\n") ? "\r\n" : "\n";
        for (int i = start; i < end; i++) {
            block.append(lines[i]);
            if (i + 1 < end || end < lines.length) {
                block.append(newline);
            } else if (yaml.endsWith("\n") || yaml.endsWith("\r\n")) {
                block.append(newline);
            }
        }
        return block.toString();
    }

    private static String replaceBlock(String yaml, String id, String replacement) {
        String[] lines = yaml.split("\\R", -1);
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            if (isTopLevelKey(lines[i]) && keyOf(lines[i]).equals(id)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return yaml;
        }
        for (int i = start + 1; i < lines.length; i++) {
            if (isTopLevelKey(lines[i])) {
                end = i;
                break;
            }
        }
        String newline = yaml.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < start; i++) {
            out.append(lines[i]).append(newline);
        }
        out.append(replacement.endsWith("\n") || replacement.endsWith("\r\n") ? replacement : replacement + newline);
        for (int i = end; i < lines.length; i++) {
            out.append(lines[i]);
            if (i + 1 < lines.length || yaml.endsWith("\n") || yaml.endsWith("\r\n")) {
                out.append(newline);
            }
        }
        return out.toString();
    }

    private static String serialize(String importYaml, String id) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(importYaml);
        } catch (Exception e) {
            return null;
        }
        YamlConfiguration one = new YamlConfiguration();
        if (yaml.isConfigurationSection(id)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            ConfigurationSection copy = one.createSection(id);
            copySection(section, copy);
        } else if (yaml.contains(id)) {
            one.set(id, yaml.get(id));
        } else {
            return null;
        }
        return one.saveToString();
    }

    private static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            if (from.isConfigurationSection(key)) {
                copySection(from.getConfigurationSection(key), to.createSection(key));
            } else {
                to.set(key, from.get(key));
            }
        }
    }

    private static boolean isTopLevelKey(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        char first = line.charAt(0);
        if (first == ' ' || first == '\t' || first == '#' || first == '-') {
            return false;
        }
        return line.indexOf(':') > 0;
    }

    private static String keyOf(String line) {
        return line.substring(0, line.indexOf(':')).trim();
    }

    private static String safeName(String requested) {
        if (requested == null) {
            return "";
        }
        String trimmed = requested.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
