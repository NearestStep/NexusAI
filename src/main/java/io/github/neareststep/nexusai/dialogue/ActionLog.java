package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.config.LogRedaction;

import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * One line per action: player, character, action name, and result.
 */
public final class ActionLog {

    private final Logger logger;
    private final java.io.File file;
    private final java.util.function.BooleanSupplier enabled;
    private volatile Supplier<Iterable<String>> secretSource = java.util.List::of;

    public ActionLog(Logger logger, java.io.File file, java.util.function.BooleanSupplier enabled) {
        this.logger = logger;
        this.file = file;
        this.enabled = enabled == null ? () -> false : enabled;
    }

    public static ActionLog noop() {
        return new ActionLog(null, null, () -> false);
    }

    /** Keys used to mask a path when appending {@code actions.log} fails. */
    public void secrets(Supplier<Iterable<String>> secrets) {
        this.secretSource = secrets == null ? java.util.List::of : secrets;
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
                Iterable<String> known = secretSource.get();
                LogRedaction.warning(logger, "Failed to append actions.log", e, known == null ? java.util.List.of() : known);
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
