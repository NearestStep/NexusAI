package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.text.Utf16;

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
            java.nio.file.Path path = file.toPath();
            AtomicFiles.createPrivate(path);
            AtomicFiles.restrictOwnerReadWrite(path);
            java.nio.file.Files.writeString(
                    path,
                    java.time.Instant.now() + " " + line + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
            AtomicFiles.restrictOwnerReadWrite(path);
        } catch (java.io.IOException e) {
            if (logger != null) {
                Iterable<String> known = secretSource.get();
                LogRedaction.warning(logger, "Failed to append actions.log", e, known == null ? java.util.List.of() : known);
            }
        }
    }

    /** A lone UTF-16 surrogate becomes U+FFFD: UTF-8 cannot encode it and the line would be lost. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return Utf16.replaceLoneSurrogates(value.replace('\n', ' ').replace('\r', ' ').trim());
    }
}
