package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.ai.PlayerInput;
import org.bukkit.Bukkit;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in prompt tokens that do not need PlaceholderAPI.
 * User {@code vars:} of the same name win. Values are read on the player's region thread.
 */
public final class ContextVariables {

    public static final Set<String> NAMES = Set.of("player", "world", "biome", "time", "weather");

    private ContextVariables() {
    }

    public static boolean isBuiltIn(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * @return built-in values, or an empty map when {@code player} is null or this thread does not own the entity
     */
    public static Map<String, String> capture(Player player) {
        if (player == null || !owns(player)) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", safe(player.getName()));
        var world = player.getWorld();
        values.put("world", world == null ? "" : safe(world.getName()));
        values.put("biome", biomeName(player));
        long ticks = world == null ? 0L : world.getTime();
        values.put("time", GameClock.format(ticks));
        boolean storm = world != null && world.hasStorm();
        boolean thunder = world != null && world.isThundering();
        values.put("weather", GameClock.weather(storm, thunder));
        return Map.copyOf(values);
    }

    /**
     * Fills built-in tokens that are not already supplied by user vars.
     * Tokens with no captured value are left in place.
     */
    public static String apply(String template, Set<String> userVarNames, Map<String, String> context) {
        if (template == null || template.isEmpty() || template.indexOf('{') < 0) {
            return template == null ? "" : template;
        }
        Map<String, String> values = context == null ? Map.of() : context;
        String result = template;
        for (String name : NAMES) {
            if (userVarNames != null && userVarNames.contains(name)) {
                continue;
            }
            if (!result.contains('{' + name + '}')) {
                continue;
            }
            String value = values.get(name);
            if (value == null) {
                continue;
            }
            result = result.replace('{' + name + '}', PlayerInput.wrap(value));
        }
        return result;
    }

    public static boolean usesBuiltIn(String template, Set<String> userVarNames) {
        if (template == null || template.indexOf('{') < 0) {
            return false;
        }
        for (String name : NAMES) {
            if (userVarNames != null && userVarNames.contains(name)) {
                continue;
            }
            if (template.contains('{' + name + '}')) {
                return true;
            }
        }
        return false;
    }

    private static boolean owns(Player player) {
        try {
            if (Bukkit.getServer() == null) {
                return false;
            }
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String biomeName(Player player) {
        try {
            Biome biome = player.getLocation().getBlock().getBiome();
            if (biome == null || biome.getKey() == null) {
                return "";
            }
            return biome.getKey().getKey().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
