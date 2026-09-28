package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts keys that exist in the default config and are missing from an existing file.
 * User values and comments already in the file are left in place.
 */
public final class ConfigMerger {

    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([^\\s:#][^:#]*):(.*)$");

    private ConfigMerger() {
    }

    public record Result(String yaml, List<String> addedKeys, boolean valid) {
        public Result(String yaml, List<String> addedKeys) {
            this(yaml, addedKeys, true);
        }
    }

    public static Result mergeMissing(String existingYaml, String defaultYaml) {
        String existing = existingYaml == null ? "" : existingYaml;
        String defaultsText = defaultYaml == null ? "" : defaultYaml;
        if (!parses(existing)) {
            return new Result(existing, List.of(), false);
        }
        String newline = existing.contains("\r\n") ? "\r\n" : "\n";
        YamlConfiguration existingConfig = new YamlConfiguration();
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            existingConfig.load(new StringReader(existing));
            defaults.load(new StringReader(defaultsText));
        } catch (IOException | InvalidConfigurationException e) {
            return new Result(existing, List.of(), false);
        }

        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!existingConfig.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return new Result(existing, List.of());
        }

        List<String> lines = new ArrayList<>(Arrays.asList(existing.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)));
        if (lines.size() == 1 && lines.getFirst().isEmpty()) {
            lines.clear();
        }
        for (String key : missing) {
            insertLeaf(lines, key, defaults.get(key));
        }
        String merged = String.join(newline, lines);
        if ((existing.endsWith("\n") || existing.endsWith("\r\n") || existing.isEmpty()) && !merged.endsWith(newline)) {
            merged = merged + newline;
        }
        return new Result(merged, List.copyOf(missing));
    }

    private static void insertLeaf(List<String> lines, String dottedKey, Object value) {
        int dot = dottedKey.lastIndexOf('.');
        String parent = dot < 0 ? "" : dottedKey.substring(0, dot);
        String leaf = dot < 0 ? dottedKey : dottedKey.substring(dot + 1);
        if (!parent.isEmpty()) {
            ensureSection(lines, parent);
        }
        Map<String, KeySite> sites = index(lines);
        KeySite parentSite = parent.isEmpty() ? null : sites.get(parent);
        int indent = parentSite == null ? 0 : parentSite.indent + 2;
        List<String> formatted = formatLeaf(leaf, value, indent);
        int at = parentSite == null ? lines.size() : endOfBlock(lines, parentSite);
        if (parentSite == null && !lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
            at = lines.size();
        }
        lines.addAll(at, formatted);
    }

    private static void ensureSection(List<String> lines, String path) {
        Map<String, KeySite> sites = index(lines);
        if (sites.containsKey(path)) {
            return;
        }
        int dot = path.lastIndexOf('.');
        String parent = dot < 0 ? "" : path.substring(0, dot);
        String name = dot < 0 ? path : path.substring(dot + 1);
        if (!parent.isEmpty()) {
            ensureSection(lines, parent);
            sites = index(lines);
        }
        KeySite parentSite = parent.isEmpty() ? null : sites.get(parent);
        int indent = parentSite == null ? 0 : parentSite.indent + 2;
        int at = parentSite == null ? lines.size() : endOfBlock(lines, parentSite);
        if (parentSite == null && !lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
            lines.add("");
            at = lines.size();
        }
        lines.add(at, " ".repeat(indent) + name + ":");
    }

    private static int endOfBlock(List<String> lines, KeySite parent) {
        int index = parent.line + 1;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (line.isBlank()) {
                int next = nextContent(lines, index + 1);
                if (next < 0 || (indentOf(lines.get(next)) <= parent.indent && !isComment(lines.get(next)))) {
                    return index;
                }
                index++;
                continue;
            }
            if (isComment(line)) {
                if (indentOf(line) <= parent.indent) {
                    return index;
                }
                index++;
                continue;
            }
            if (indentOf(line) <= parent.indent) {
                return index;
            }
            index++;
        }
        return lines.size();
    }

    private static int nextContent(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, KeySite> index(List<String> lines) {
        Map<String, KeySite> sites = new LinkedHashMap<>();
        Deque<KeySite> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || isComment(line)) {
                continue;
            }
            String trimmed = stripIndent(line);
            if (trimmed.startsWith("-")) {
                continue;
            }
            Matcher matcher = KEY_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int indent = indentOf(line);
            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            String key = unquote(matcher.group(2).trim());
            String path = stack.isEmpty() ? key : stack.peek().path + "." + key;
            KeySite site = new KeySite(indent, i, path);
            sites.put(path, site);
            stack.push(site);
        }
        return sites;
    }

    private static List<String> formatLeaf(String leaf, Object value, int indent) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("v", value);
        String dumped = yaml.saveToString().replace("\r\n", "\n");
        List<String> formatted = new ArrayList<>();
        String pad = " ".repeat(Math.max(0, indent));
        for (String line : dumped.split("\n", -1)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("v:")) {
                formatted.add(pad + leaf + line.substring(1));
            } else {
                formatted.add(pad + line);
            }
        }
        if (formatted.isEmpty()) {
            formatted.add(pad + leaf + ":");
        }
        return formatted;
    }

    private static boolean parses(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(new StringReader(yaml == null ? "" : yaml));
            return true;
        } catch (IOException | InvalidConfigurationException e) {
            return false;
        }
    }

    private static boolean isComment(String line) {
        return stripIndent(line).startsWith("#");
    }

    private static String stripIndent(String line) {
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c != ' ' && c != '\t') {
                break;
            }
            index++;
        }
        return line.substring(index);
    }

    private static int indentOf(String line) {
        int indent = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                indent++;
            } else if (c == '\t') {
                indent += 2;
            } else {
                break;
            }
        }
        return indent;
    }

    private static String unquote(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static final class KeySite {
        private final int indent;
        private final int line;
        private final String path;

        private KeySite(int indent, int line, String path) {
            this.indent = indent;
            this.line = line;
            this.path = path;
        }
    }
}
