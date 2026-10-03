package io.github.neareststep.nexusai.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Copies a config file to {@code <file>.bak} before a rewrite.
 * When that backup already exists, the new copy is {@code <file>.bak.<timestamp>}.
 */
public final class FileBackup {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private FileBackup() {
    }

    public static Path backup(Path source) throws IOException {
        Path bak = source.resolveSibling(source.getFileName().toString() + ".bak");
        if (Files.exists(bak)) {
            bak = source.resolveSibling(source.getFileName().toString() + ".bak." + STAMP.format(Instant.now()));
            int salt = 0;
            while (Files.exists(bak)) {
                salt++;
                bak = source.resolveSibling(source.getFileName().toString() + ".bak." + STAMP.format(Instant.now()) + "-" + salt);
            }
        }
        Files.copy(source, bak, StandardCopyOption.COPY_ATTRIBUTES);
        AtomicFiles.copyPosix(source, bak);
        return bak;
    }

    /**
     * Copies {@code source} to a backup, then replaces its bytes with {@code yaml}.
     *
     * @return the backup path
     */
    public static Path replace(Path source, String yaml) throws IOException {
        Path backup = backup(source);
        AtomicFiles.replaceContents(source, yaml == null ? "" : yaml);
        return backup;
    }
}
