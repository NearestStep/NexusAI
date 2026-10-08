package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.context.RegionOwnership;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

/**
 * Replaces {@code {token}} markers in AI answers using configured PlaceholderAPI templates.
 */
public final class VarSubstitutor {

    /**
     * Replaces PlaceholderAPI in tests. Production leaves this unset and calls PlaceholderAPI
     * after the owning-thread check.
     */
    @FunctionalInterface
    interface Lookup {
        String apply(Player player, String template);
    }

    private static volatile Lookup lookup;

    private VarSubstitutor() {
    }

    static void installLookup(Lookup next) {
        lookup = next;
    }

    static void resetLookup() {
        lookup = null;
    }

    public static String apply(String answer, Map<String, String> vars, Player player) {
        Objects.requireNonNull(answer, "answer");
        if (vars == null || vars.isEmpty()) {
            return answer;
        }
        String result = answer;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String token = '{' + entry.getKey() + '}';
            result = result.replace(token, resolve(player, entry.getValue()));
        }
        return result;
    }

    /**
     * Builds the HTTP prompt appendix that tells the model to emit brace tokens.
     */
    public static String appendVarsRules(String prompt, Map<String, String> vars) {
        Objects.requireNonNull(prompt, "prompt");
        if (vars == null || vars.isEmpty()) {
            return prompt;
        }
        StringBuilder sb = new StringBuilder(prompt);
        sb.append("\n\nRules: In your answer, use these exact brace tokens where personalization belongs. ")
                .append("Do not invent real values for them. Leave the braces unchanged:");
        for (String key : vars.keySet()) {
            sb.append("\n- {").append(key).append('}');
        }
        return sb.toString();
    }

    public static String resolve(Player player, String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        if (!template.contains("%")) {
            return template;
        }
        if (player == null) {
            return "";
        }
        if (!RegionOwnership.owned(player)) {
            return "";
        }
        Lookup current = lookup;
        if (current != null) {
            String value = current.apply(player, template);
            return value == null ? "" : value;
        }
        if (Bukkit.getPluginManager() == null
                || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return "";
        }
        return PlaceholderAPI.setPlaceholders(player, template);
    }
}