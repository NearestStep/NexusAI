package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.text.Utf16;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Append-only flag log at {@code plugins/NexusAI/moderation.log}.
 * On a POSIX file system the file is owner-read/write ({@code 0600}), including after a looser mode
 * or when a rotated file is created again.
 */
public final class ModerationLog {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final java.io.File file;
    private final Logger logger;
    private final Object lock = new Object();
    private volatile Supplier<Iterable<String>> secretSource = java.util.List::of;

    public ModerationLog(java.io.File file, Logger logger) {
        this.file = file;
        this.logger = logger == null ? Logger.getLogger("nexusai.moderation") : logger;
    }

    /** Keys used to mask a path when writing {@code moderation.log} fails. */
    public void secrets(Supplier<Iterable<String>> secrets) {
        this.secretSource = secrets == null ? java.util.List::of : secrets;
    }

    public void append(UUID playerId, String playerName, String message, String category, String reason) {
        String line = CLOCK.format(ZonedDateTime.now(ZoneId.systemDefault()))
                + " | " + field(playerName)
                + " | " + (playerId == null ? "" : playerId)
                + " | " + field(category)
                + " | " + field(reason)
                + " | " + field(message)
                + System.lineSeparator();
        synchronized (lock) {
            try {
                java.nio.file.Path path = file.toPath();
                AtomicFiles.createPrivate(path);
                AtomicFiles.restrictOwnerReadWrite(path);
                Files.writeString(
                        path,
                        line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
                AtomicFiles.restrictOwnerReadWrite(path);
            } catch (IOException e) {
                Iterable<String> known = secretSource.get();
                LogRedaction.warning(logger, "Failed to write moderation.log", e, known == null ? java.util.List.of() : known);
            }
        }
    }

    /** One line per flag. A lone UTF-16 surrogate becomes U+FFFD: UTF-8 cannot encode it and the line would be lost. */
    private static String field(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Utf16.replaceLoneSurrogates(value.replace('\r', ' ').replace('\n', ' ').replace('|', '/'));
    }
}
