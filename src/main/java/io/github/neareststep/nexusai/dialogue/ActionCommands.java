package io.github.neareststep.nexusai.dialogue;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Fills {@code {player}} and {@code {uuid}} in an admin command template.
 * Model arguments are never inserted.
 */
public final class ActionCommands {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    private ActionCommands() {
    }

    public static boolean safePlayerName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    /**
     * @return the command without a leading slash, or {@code null} when the player name is unsafe
     *         or the template contains a newline
     */
    public static String render(String template, String playerName, UUID playerId) {
        if (template == null || template.isBlank() || !safePlayerName(playerName)) {
            return null;
        }
        String uuid = playerId == null ? "" : playerId.toString();
        String rendered = template.replace("{player}", playerName).replace("{uuid}", uuid);
        if (rendered.indexOf('\n') >= 0 || rendered.indexOf('\r') >= 0) {
            return null;
        }
        String trimmed = rendered.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
