package io.github.neareststep.nexusai.dialogue;

import java.util.logging.Logger;

/**
 * One line per action: player, character, action name, and result.
 */
public final class ActionLog {

    private final Logger logger;
    private final java.io.File file;
    private final java.util.function.BooleanSupplier enabled;

    public ActionLog(Logger logger, java.io.File file, java.util.function.BooleanSupplier enabled) {
        this.logger = logger;
        this.file = file;
        this.enabled = enabled == null ? () -> false : enabled;
    }

    public static ActionLog noop() {
        return new ActionLog(null, null, () -> false);
    }

    public synchronized void record(String player, String character, String action, String result) {
        String line = "action player=" + sanitize(player)
                + " character=" + sanitize(character)
                + " action=" + sanitize(action)
                + " result=" + sanitize(result);
        if (logger != null) {
            logger.info(line);
        }
        if (enabled == null || !enabled.getAsBoolean() || file == null) {
            return;
        }
        try {
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            java.nio.file.Files.writeString(
                    file.toPath(),
                    java.time.Instant.now() + " " + line + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (java.io.IOException e) {
            if (logger != null) {
                logger.warning("Failed to append actions.log: " + e.getMessage());
            }
        }
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
