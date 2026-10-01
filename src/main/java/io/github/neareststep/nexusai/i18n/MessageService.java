package io.github.neareststep.nexusai.i18n;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Loads {@code lang/{locale}.yml} with English fallback and optional data-folder overrides.
 */
public final class MessageService {

    private static final String DEFAULT_LOCALE = "en";

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
        String prefix = colorize(raw("prefix"));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("prefix", prefix);
        if (placeholders != null) {
            values.putAll(placeholders);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace('{' + entry.getKey() + '}', entry.getValue() == null ? "" : entry.getValue());
        }
        return message;
    }

    public String format(String key) {
        return format(key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(sender, "sender");
        sender.sendMessage(format(key, placeholders));
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
        if (userPresent) {
            try {
                user.load(new InputStreamReader(java.nio.file.Files.newInputStream(userFile.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load locale file " + userFile.getPath(), e);
                user = new YamlConfiguration();
                userPresent = false;
            }
        }
        if (!bundledPresent && !userPresent && !DEFAULT_LOCALE.equals(localeCode) && plugin != null) {
            plugin.getLogger().warning("Locale file missing in jar: lang/" + localeCode + ".yml — using English.");
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
            plugin.getLogger().log(Level.WARNING, "Failed to load locale resource " + resourcePath, e);
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
}
