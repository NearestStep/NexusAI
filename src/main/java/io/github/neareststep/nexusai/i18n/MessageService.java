package io.github.neareststep.nexusai.i18n;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.SecretMask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code lang/{locale}.yml} with English fallback and optional data-folder overrides.
 */
public final class MessageService {

    private static final String DEFAULT_LOCALE = "en";
    /**
     * Model replies. Inserted with {@link Component#text(String)} after the template is coloured,
     * so {@code &}, {@code §}, hex, and MiniMessage in the reply are not parsed.
     */
    private static final Set<String> PLAIN_PLACEHOLDERS = Set.of("reply", "answer");
    /**
     * Left as written when substituted. {@code prefix} is the locale colour template.
     * {@code reply} and {@code answer} are inserted as plain text after the template is
     * deserialized, so they are not parsed as legacy codes.
     * Every other placeholder is data (a character or prompt id, a player name, an error,
     * a config value, an import name, moderation text) and is stripped before it is
     * written into the legacy string.
     */
    private static final Set<String> UNSANITIZED_PLACEHOLDERS = Set.of("prefix", "reply", "answer");
    private static final Pattern YAML_LOCATION = Pattern.compile("line \\d+, column \\d+");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private String locale = DEFAULT_LOCALE;
    private FileConfiguration primary = new YamlConfiguration();
    private FileConfiguration fallback = new YamlConfiguration();
    private String filledLogSignature = "";

    public MessageService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * Test/helper constructor without a live plugin instance.
     */
    MessageService(FileConfiguration primary, FileConfiguration fallback, String locale) {
        this.plugin = null;
        this.primary = primary == null ? new YamlConfiguration() : primary;
        this.fallback = fallback == null ? new YamlConfiguration() : fallback;
        this.locale = normalizeLocale(locale);
    }

    public void reload(String requestedLocale) {
        this.locale = normalizeLocale(requestedLocale);
        installBundledLocales();
        this.fallback = readBundled(DEFAULT_LOCALE);
        this.primary = loadLocale(this.locale);
    }

    /**
     * Copies missing bundled locale files into {@code plugins/NexusAI/lang}. Existing files stay as they are.
     */
    public void installBundledLocales() {
        if (plugin == null) {
            return;
        }
        Path langDir = new File(plugin.getDataFolder(), "lang").toPath();
        try {
            LocaleFiles.extractMissing(langDir, code -> plugin.getResource("lang/" + code + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not extract bundled locale files", e);
        }
    }

    public String getLocale() {
        return locale;
    }

    public String raw(String key) {
        Objects.requireNonNull(key, "key");
        String value = primary.getString(key);
        if (value == null || value.isBlank()) {
            value = fallback.getString(key);
        }
        return value == null ? key : value;
    }

    public String format(String key, Map<String, String> placeholders) {
        // Colour codes belong to the template and the prefix. Substituted values, including a
        // model reply, are inserted afterwards so their '&' text is not turned into formatting.
        String message = colorize(raw(key));
        Map<String, String> values = placeholderValues(placeholders);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace('{' + entry.getKey() + '}', inserted(entry.getKey(), entry.getValue()));
        }
        return message;
    }

    /**
     * Same substitution as {@link #format(String, Map)}, but {@code {reply}} and {@code {answer}}
     * are {@link Component#text(String)} nodes. The template is legacy-deserialized first, so its
     * {@code &} codes become colours, and the model reply is not passed through
     * {@link ChatColor#translateAlternateColorCodes(char, String)} or MiniMessage.
     */
    public Component component(String key, Map<String, String> placeholders) {
        String template = colorize(raw(key));
        Map<String, String> values = placeholderValues(placeholders);
        Map<Character, String> plain = new LinkedHashMap<>();
        char mark = '\uE000';
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String needle = '{' + entry.getKey() + '}';
            if (!template.contains(needle)) {
                continue;
            }
            String value = inserted(entry.getKey(), entry.getValue());
            if (PLAIN_PLACEHOLDERS.contains(entry.getKey())) {
                plain.put(mark, value);
                template = template.replace(needle, String.valueOf(mark));
                mark++;
            } else {
                template = template.replace(needle, value);
            }
        }
        return replacePlain(LEGACY.deserialize(template), plain);
    }

    public String format(String key) {
        return format(key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(sender, "sender");
        sender.sendMessage(component(key, placeholders));
    }

    /**
     * Anything except {@code prefix}, {@code reply}, and {@code answer} is written into the
     * legacy template, so a section sign in it becomes a colour and a click tag can survive
     * for a later parser. Strip those values first. {@code reply} and {@code answer} stay raw
     * here; {@link #component(String, Map)} inserts them as plain text later.
     */
    private static String inserted(String key, String value) {
        String text = value == null ? "" : value;
        if (UNSANITIZED_PLACEHOLDERS.contains(key)) {
            return text;
        }
        return PlayerInput.stripSectionSigns(text);
    }

    private Map<String, String> placeholderValues(Map<String, String> placeholders) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("prefix", colorize(raw("prefix")));
        if (placeholders != null) {
            values.putAll(placeholders);
        }
        return values;
    }

    private static Component replacePlain(Component component, Map<Character, String> plain) {
        if (plain.isEmpty()) {
            return component;
        }
        List<Component> children = new ArrayList<>();
        for (Component child : component.children()) {
            children.add(replacePlain(child, plain));
        }
        if (!(component instanceof TextComponent text) || !containsMark(text.content(), plain)) {
            return component.children(children);
        }
        Component built = Component.empty();
        StringBuilder literal = new StringBuilder();
        String content = text.content();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            String replacement = plain.get(c);
            if (replacement == null) {
                literal.append(c);
                continue;
            }
            if (!literal.isEmpty()) {
                built = built.append(Component.text(literal.toString()).style(text.style()));
                literal.setLength(0);
            }
            built = built.append(Component.text(replacement).style(text.style()));
        }
        if (!literal.isEmpty()) {
            built = built.append(Component.text(literal.toString()).style(text.style()));
        }
        for (Component child : children) {
            built = built.append(child);
        }
        return built;
    }

    private static boolean containsMark(String content, Map<Character, String> plain) {
        for (int i = 0; i < content.length(); i++) {
            if (plain.containsKey(content.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String colorize(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    public static String normalizeLocale(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_LOCALE;
        }
        String[] parts = requested.trim().replace('-', '_').split("_", 2);
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length == 1 || parts[1].isBlank()) {
            return language;
        }
        return language + "_" + parts[1].toUpperCase(Locale.ROOT);
    }

    private FileConfiguration loadLocale(String localeCode) {
        boolean bundledPresent = bundledExists(localeCode);
        YamlConfiguration bundled = bundledPresent ? readBundled(localeCode) : new YamlConfiguration();
        YamlConfiguration english = DEFAULT_LOCALE.equals(localeCode) ? bundled : readBundled(DEFAULT_LOCALE);
        File userFile = plugin == null ? null : new File(plugin.getDataFolder(), "lang/" + localeCode + ".yml");
        boolean userPresent = userFile != null && userFile.isFile();
        YamlConfiguration user = new YamlConfiguration();
        boolean brokenUserFile = false;
        if (userPresent) {
            try {
                user.load(new InputStreamReader(java.nio.file.Files.newInputStream(userFile.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                String fallback = bundledPresent
                        ? "Using the bundled " + localeCode + " locale."
                        : "Using English.";
                warnBrokenLocale(plugin.getLogger(), userFile, e, fallback, configuredSecrets());
                user = new YamlConfiguration();
                userPresent = false;
                brokenUserFile = true;
            }
        }
        if (!brokenUserFile && !bundledPresent && !userPresent && !DEFAULT_LOCALE.equals(localeCode) && plugin != null) {
            File expected = userFile != null ? userFile : new File("lang/" + localeCode + ".yml");
            warnMissingLocale(plugin.getLogger(), localeCode, expected, configuredSecrets());
        }
        YamlConfiguration merged = merge(user, bundledPresent ? bundled : new YamlConfiguration(), english);
        int filled = userPresent ? filledFromDefaults(user, merged) : 0;
        logFilled(localeCode, filled);
        return merged;
    }

    private void logFilled(String localeCode, int filled) {
        if (plugin == null || filled <= 0) {
            return;
        }
        String signature = localeCode + ":" + filled;
        if (signature.equals(filledLogSignature)) {
            return;
        }
        filledLogSignature = signature;
        plugin.getLogger().info("Locale " + localeCode + " is missing " + filled
                + " message keys. Filled them from the bundled locale and English.");
    }

    private boolean bundledExists(String localeCode) {
        if (plugin == null) {
            return DEFAULT_LOCALE.equals(localeCode);
        }
        try (InputStream in = plugin.getResource("lang/" + localeCode + ".yml")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private YamlConfiguration readBundled(String localeCode) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (plugin == null) {
            return yaml;
        }
        String resourcePath = "lang/" + localeCode + ".yml";
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            warnBrokenLocale(plugin.getLogger(), new File(resourcePath), e, "Using English.", configuredSecrets());
        }
        return yaml;
    }

    /**
     * Merge order is user file, then the bundled file for that locale, then bundled English.
     * A custom locale has no bundled file, so missing keys come from English.
     */
    public static YamlConfiguration merge(FileConfiguration user, FileConfiguration bundledLocale, FileConfiguration english) {
        YamlConfiguration merged = new YamlConfiguration();
        overlay(merged, english);
        overlay(merged, bundledLocale);
        overlay(merged, user);
        return merged;
    }

    /**
     * Leaf keys present in {@code merged} and absent from {@code user}.
     */
    public static int filledFromDefaults(FileConfiguration user, FileConfiguration merged) {
        if (merged == null) {
            return 0;
        }
        int filled = 0;
        for (String key : merged.getKeys(true)) {
            if (merged.isConfigurationSection(key)) {
                continue;
            }
            if (user == null || !user.contains(key)) {
                filled++;
            }
        }
        return filled;
    }

    private static void overlay(YamlConfiguration target, FileConfiguration source) {
        if (source == null) {
            return;
        }
        for (String key : source.getKeys(true)) {
            if (!source.isConfigurationSection(key)) {
                target.set(key, source.get(key));
            }
        }
    }

    /** Package-visible factory for unit tests. */
    public static MessageService forTest(FileConfiguration primary, FileConfiguration fallback, String locale) {
        return new MessageService(primary, fallback, locale);
    }

    /**
     * Two warning lines: the missing file and the fallback, then the bundled locale codes.
     * There is no stack trace. The file is not described as missing from the jar alone.
     */
    static void warnMissingLocale(Logger logger, String localeCode, File expectedFile) {
        warnMissingLocale(logger, localeCode, expectedFile, List.of());
    }

    static void warnMissingLocale(Logger logger, String localeCode, File expectedFile, Iterable<String> secrets) {
        if (logger == null) {
            return;
        }
        String path = expectedFile == null ? "lang/" + localeCode + ".yml" : expectedFile.getPath();
        logger.warning("Locale '" + localeCode + "' is not available: " + SecretMask.redact(path, secrets)
                + " does not exist and lang/" + localeCode + ".yml is not bundled. Using English.");
        logger.warning("Bundled locales: " + String.join(", ", LocaleFiles.BUNDLED) + ".");
    }

    /**
     * One warning line naming the file, the parser reason (including line and column when
     * the message has them), and the fallback. The stack trace is logged at {@link Level#FINE} only.
     */
    static void warnBrokenLocale(Logger logger, File file, Exception error, String fallback) {
        warnBrokenLocale(logger, file, error, fallback, List.of());
    }

    static void warnBrokenLocale(Logger logger, File file, Exception error, String fallback, Iterable<String> secrets) {
        if (logger == null) {
            return;
        }
        String path = file == null ? "(unknown locale file)" : file.getPath();
        String shown = SecretMask.redact(path, secrets);
        String reason = SecretMask.redact(yamlReason(error), secrets);
        String where = fallback == null || fallback.isBlank() ? "Using English." : fallback;
        logger.warning("Could not read " + shown + " (" + reason + "). " + where);
        logger.log(Level.FINE, "Could not read " + shown, fineCause(error, secrets, !shown.equals(path)));
    }

    private Iterable<String> configuredSecrets() {
        if (plugin instanceof io.github.neareststep.nexusai.NexusAI nexus) {
            PluginConfig config = nexus.getPluginConfig();
            if (config != null) {
                return config.configuredSecrets();
            }
        }
        return List.of();
    }

    /**
     * Keeps the original throwable when neither its message nor the logged path holds a secret,
     * so a locale parser failure still shows the same stack at FINE. A path that changed under
     * the mask is not attached raw.
     */
    private static Throwable fineCause(Throwable error, Iterable<String> secrets, boolean pathChanged) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        boolean messageChanged = message != null && !message.equals(SecretMask.redact(message, secrets));
        if (!pathChanged && !messageChanged) {
            return error;
        }
        return LogRedaction.redactThrowable(error, secrets);
    }

    static String yamlReason(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            Throwable cause = error.getCause();
            if (cause != null && cause != error && cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            } else {
                return error.getClass().getSimpleName();
            }
        }
        String flat = message.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
        Matcher location = YAML_LOCATION.matcher(flat);
        String where = location.find() ? location.group() : "";
        if (flat.length() > 220) {
            flat = flat.substring(0, 217).strip() + "...";
        }
        if (!where.isEmpty() && !flat.contains(where)) {
            flat = flat + " " + where;
        }
        return flat;
    }
}
