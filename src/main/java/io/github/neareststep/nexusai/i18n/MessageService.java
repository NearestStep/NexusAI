package io.github.neareststep.nexusai.i18n;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        this.fallback = loadBundle(DEFAULT_LOCALE);
        this.primary = DEFAULT_LOCALE.equals(this.locale) ? this.fallback : loadBundle(this.locale);
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
        String message = raw(key);
        String prefix = raw("prefix");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("prefix", prefix);
        if (placeholders != null) {
            values.putAll(placeholders);
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace('{' + entry.getKey() + '}', entry.getValue() == null ? "" : entry.getValue());
        }
        return colorize(message);
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
        return requested.trim().replace('-', '_');
    }

    private FileConfiguration loadBundle(String localeCode) {
        YamlConfiguration yaml = new YamlConfiguration();

        if (plugin != null) {
            String resourcePath = "lang/" + localeCode + ".yml";
            try (InputStream in = plugin.getResource(resourcePath)) {
                if (in != null) {
                    yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                } else if (!DEFAULT_LOCALE.equals(localeCode)) {
                    plugin.getLogger().warning("Locale file missing in jar: " + resourcePath + " — using English.");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load locale resource " + resourcePath, e);
            }

            File override = new File(plugin.getDataFolder(), "lang/" + localeCode + ".yml");
            if (override.isFile()) {
                try {
                    YamlConfiguration disk = YamlConfiguration.loadConfiguration(override);
                    for (String key : disk.getKeys(true)) {
                        if (!disk.isConfigurationSection(key)) {
                            yaml.set(key, disk.get(key));
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load locale override " + override.getPath(), e);
                }
            }
        }

        return yaml;
    }

    /** Package-visible factory for unit tests. */
    public static MessageService forTest(FileConfiguration primary, FileConfiguration fallback, String locale) {
        return new MessageService(primary, fallback, locale);
    }
}
