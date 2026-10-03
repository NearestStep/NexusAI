package io.github.neareststep.nexusai.config;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads a raw API-key file. One key per non-empty line. Comment lines and surrounding
 * whitespace are ignored. The file contents are never placed in a warning.
 */
public final class KeyFiles {

    public static final int MAX_BYTES = 64 * 1024;

    private KeyFiles() {
    }

    public record Loaded(List<String> keys, List<String> warnings) {
        public Loaded {
            keys = keys == null ? List.of() : List.copyOf(keys);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public static Loaded read(Path path) {
        List<String> warnings = new ArrayList<>();
        if (path == null) {
            warnings.add("API key file path is empty.");
            return new Loaded(List.of(), warnings);
        }
        String shown = path.toString();
        if (!Files.isRegularFile(path)) {
            warnings.add("API key file was not found: " + shown);
            return new Loaded(List.of(), warnings);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            warnings.add("API key file could not be read: " + shown);
            return new Loaded(List.of(), warnings);
        }
        if (size > MAX_BYTES) {
            warnings.add("API key file is larger than 64KB and was not read: " + shown);
            return new Loaded(List.of(), warnings);
        }
        warnIfGroupOrWorldReadable(path, warnings);
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (isNotUtf8(e)) {
                warnings.add("API key file is not valid UTF-8 and was not read: " + shown);
            } else {
                warnings.add("API key file could not be read: " + shown);
            }
            return new Loaded(List.of(), warnings);
        }
        List<String> keys = new ArrayList<>();
        int number = 0;
        for (String line : text.split("\\R", -1)) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            if (hasInternalWhitespace(trimmed) || trimmed.indexOf(':') >= 0) {
                warnings.add("line " + number + " of " + shown + " does not look like a raw key");
                continue;
            }
            keys.add(trimmed);
        }
        return new Loaded(keys, warnings);
    }

    private static void warnIfGroupOrWorldReadable(Path path, List<String> warnings) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.add("chmod 600 " + path);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX volume. The check is optional.
        }
    }

    private static boolean hasInternalWhitespace(String trimmed) {
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNotUtf8(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CharacterCodingException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }
}
