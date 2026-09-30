package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code dialogue:} and {@code actions:} from one prompt section.
 */
public final class DialogueBinding {

    private DialogueBinding() {
    }

    public static Result read(String id, ConfigurationSection section, List<String> warnings) {
        DialogueProfile profile = DialogueProfile.absent();
        List<CharacterAction> actions = List.of();
        if (section == null) {
            return new Result(profile, actions);
        }
        if (section.isConfigurationSection("dialogue")) {
            profile = readProfile(id, section.getConfigurationSection("dialogue"), warnings);
        } else if (section.contains("dialogue")) {
            warnings.add("Prompt '" + id + "' dialogue must be a map. It was ignored.");
        }
        if (section.contains("actions")) {
            actions = readActions(id, section, warnings);
        }
        return new Result(profile, actions);
    }

    private static DialogueProfile readProfile(String id, ConfigurationSection section, List<String> warnings) {
        String greeting = null;
        if (section.contains("greeting")) {
            Object raw = section.get("greeting");
            greeting = raw == null ? null : String.valueOf(raw);
        }
        Integer memoryTurns = readBounded(id, section, "memory-turns", 1, 16, warnings);
        Integer timeout = readBounded(id, section, "session-timeout-seconds", 0, 86_400, warnings);
        Integer radius = readBounded(id, section, "leave-radius", 0, 10_000, warnings);
        Integer replies = readBounded(id, section, "max-replies", 1, 1_000, warnings);
        Integer cooldown = readBounded(id, section, "message-cooldown-millis", 0, 600_000, warnings);
        for (String key : section.getKeys(false)) {
            if (!key.equals("greeting")
                    && !key.equals("memory-turns")
                    && !key.equals("session-timeout-seconds")
                    && !key.equals("leave-radius")
                    && !key.equals("max-replies")
                    && !key.equals("message-cooldown-millis")) {
                warnings.add("Prompt '" + id + "' dialogue has unknown setting '" + key + "'. It was ignored.");
            }
        }
        return new DialogueProfile(true, greeting, memoryTurns, timeout, radius, replies, cooldown);
    }

    private static List<CharacterAction> readActions(String id, ConfigurationSection section, List<String> warnings) {
        List<?> raw = section.getList("actions");
        if (raw == null) {
            warnings.add("Prompt '" + id + "' actions must be a list. They were ignored.");
            return List.of();
        }
        List<CharacterAction> actions = new ArrayList<>();
        int index = 0;
        for (Object item : raw) {
            index++;
            if (!(item instanceof Map<?, ?> map)) {
                warnings.add("Prompt '" + id + "' action #" + index + " must be a map. It was ignored.");
                continue;
            }
            String name = text(map.get("name"));
            String description = text(map.get("description"));
            String command = text(map.get("command"));
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                warnings.add("Prompt '" + id + "' action #" + index + " has an invalid name. It was ignored.");
                continue;
            }
            if (description == null) {
                warnings.add("Prompt '" + id + "' action '" + name + "' has no description. It was ignored.");
                continue;
            }
            if (command == null) {
                warnings.add("Prompt '" + id + "' action '" + name + "' has no command. It was ignored.");
                continue;
            }
            boolean duplicate = false;
            for (CharacterAction existing : actions) {
                if (existing.name().equals(name)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                warnings.add("Prompt '" + id + "' action '" + name + "' is listed more than once. The later copy was ignored.");
                continue;
            }
            String as = text(map.get("as"));
            boolean console = false;
            if (as != null) {
                String normalized = as.toLowerCase(Locale.ROOT);
                if ("console".equals(normalized)) {
                    console = true;
                } else if (!"player".equals(normalized)) {
                    warnings.add("Prompt '" + id + "' action '" + name + "' has unknown as '" + as + "'. It will run as the player.");
                }
            }
            int cooldown = number(map.get("cooldown-seconds"), 0);
            int daily = number(map.get("daily-limit"), 0);
            if (cooldown < 0 || daily < 0) {
                warnings.add("Prompt '" + id + "' action '" + name + "' has a negative limit. Zero was used.");
                cooldown = Math.max(0, cooldown);
                daily = Math.max(0, daily);
            }
            String permission = text(map.get("permission"));
            if (permission != null && !permission.matches("[A-Za-z0-9._*-]+")) {
                warnings.add("Prompt '" + id + "' action '" + name + "' has an invalid permission. The action was ignored.");
                continue;
            }
            actions.add(new CharacterAction(name, description, command, console, cooldown, daily, permission));
        }
        return List.copyOf(actions);
    }

    private static Integer readBounded(
            String id,
            ConfigurationSection section,
            String key,
            int min,
            int max,
            List<String> warnings
    ) {
        if (!section.contains(key)) {
            return null;
        }
        Object raw = section.get(key);
        Integer value = raw instanceof Number number ? number.intValue() : parse(raw);
        if (value == null || value < min || value > max) {
            warnings.add("Prompt '" + id + "' dialogue " + key + " is invalid. The config.yml value will be used.");
            return null;
        }
        return value;
    }

    private static Integer parse(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int number(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        Integer parsed = parse(raw);
        return parsed == null ? fallback : parsed;
    }

    private static String text(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    public record Result(DialogueProfile profile, List<CharacterAction> actions) {
    }
}
