package io.github.neareststep.nexusai.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;

/**
 * Replaces {@code {token}} markers in AI answers using configured PlaceholderAPI templates.
 */
public final class VarSubstitutor {

    private VarSubstitutor() {
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

    static String resolve(Player player, String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        if (!template.contains("%")) {
            return template;
        }
        if (player == null) {
            return "";
        }
        if (Bukkit.getPluginManager() == null
                || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return "";
        }
        return PlaceholderAPI.setPlaceholders(player, template);
    }
}