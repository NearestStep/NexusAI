package io.github.neareststep.nexusai.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/**
 * Replaces a file by writing a temporary sibling and moving it into place.
 * An atomic move creates a new inode, so the temp file must already carry the mode
 * the target should keep. On a POSIX file system a new file is owner-read/write ({@code 0600}).
 * An existing target keeps its mode and, when the process may change it, its owner.
 * A volume without POSIX attributes is left on the platform default.
 */
public final class AtomicFiles {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private AtomicFiles() {
    }

    /**
     * Writes {@code source} to {@code target} only when {@code target} is missing.
     * The new file is owner-read/write ({@code 0600}) on a POSIX file system.
     * An existing file, including its mode, is left untouched. A non-POSIX volume keeps the platform default.
     */
    public static void installPrivate(Path target, InputStream source) throws IOException {
        if (target == null || Files.exists(target)) {
            return;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        createPrivate(target);
        if (source == null) {
            return;
        }
        try {
            preserving(target, () -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING));
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // The caller still sees the original failure.
            }
            if (e instanceof IOException io) {
                throw io;
            }
            throw (RuntimeException) e;
        }
    }

    /**
     * Creates {@code path} as mode {@code 0600} when the default file system is POSIX.
     * Does nothing when the path already exists. A non-POSIX volume gets an ordinary create.
     */
    public static void createPrivate(Path path) throws IOException {
        if (path == null || Files.exists(path)) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
        }
    }

    /**
     * Copies POSIX permissions and, when permitted, the owner from {@code source} onto {@code dest}.
     * No-op when either path is missing or the file system has no POSIX view.
     */
    /**
     * Sets mode {@code 0600} on {@code path} when a POSIX view is available.
     */
    public static void restrictOwnerReadWrite(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        try {
            view.setPermissions(OWNER_READ_WRITE);
        } catch (UnsupportedOperationException | IOException ignored) {
            // A non-POSIX volume has no mode to set.
        }
    }

    public static void copyPosix(Path source, Path dest) {
        if (source == null || dest == null || !Files.isRegularFile(source) || !Files.exists(dest)) {
            return;
        }
        PosixFileAttributeView from = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        PosixFileAttributeView to = Files.getFileAttributeView(dest, PosixFileAttributeView.class);
        if (from == null || to == null) {
            return;
        }
        try {
            PosixFileAttributes attrs = from.readAttributes();
            try {
                to.setPermissions(attrs.permissions());
            } catch (UnsupportedOperationException | IOException ignored) {
                // The bytes can still move. A non-POSIX volume has no mode to copy.
            }
            try {
                UserPrincipal owner = attrs.owner();
                if (owner != null) {
                    to.setOwner(owner);
                }
            } catch (UnsupportedOperationException | IOException ignored) {
                // Changing owner needs privilege the server process often does not have.
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Same as above: the replace still proceeds.
        }
    }

    /**
     * Gives {@code temporary} the target's POSIX mode and owner when {@code target} already exists,
     * then moves it into place. A missing target keeps the mode {@code temporary} already has
     * (owner-read/write when {@link #createPrivate} created it).
     */
    /**
     * Flushes {@code temporary}, renames it onto {@code target}, then flushes the parent directory.
     * A failed flush does not abort the rename. Permissions stay with {@link #moveReplacing}.
     */
    public static void durableReplace(Path temporary, Path target) throws IOException {
        if (temporary == null || target == null) {
            throw new IOException("missing path");
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // A failed fsync must not abort the rename or leave the temp file as the only copy.
        }
        moveReplacing(temporary, target);
        Path parent = target.getParent() == null ? Path.of(".") : target.getParent();
        try (FileChannel channel = FileChannel.open(parent, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // Directory fsync is best effort. The temp file was already forced.
        }
    }

    public static void moveReplacing(Path temporary, Path target) throws IOException {
        if (target != null && Files.isRegularFile(target)) {
            copyPosix(target, temporary);
        } else {
            restrictOwnerReadWrite(temporary);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Rewrites {@code target} in place and puts its previous POSIX mode and owner back.
     * A new file is not forced to {@code 0600}; callers that create a secret use {@link #createPrivate}.
     */
    public static void replaceContents(Path target, String text) throws IOException {
        boolean existed = target != null && Files.isRegularFile(target);
        PosixSnapshot snapshot = existed ? PosixSnapshot.capture(target) : null;
        try {
            Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
        } finally {
            if (snapshot != null) {
                snapshot.apply(target);
            }
        }
    }

    /**
     * Runs {@code writer} and then restores POSIX mode and owner when {@code target} already existed.
     */
    public static void preserving(Path target, IoAction writer) throws IOException {
        boolean existed = target != null && Files.isRegularFile(target);
        PosixSnapshot snapshot = existed ? PosixSnapshot.capture(target) : null;
        try {
            writer.run();
        } finally {
            if (snapshot != null) {
                snapshot.apply(target);
            }
        }
    }

    @FunctionalInterface
    public interface IoAction {
        void run() throws IOException;
    }

    private record PosixSnapshot(Set<PosixFilePermission> permissions, UserPrincipal owner) {
        static PosixSnapshot capture(Path path) {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
                return null;
            }
            try {
                PosixFileAttributes attrs = view.readAttributes();
                return new PosixSnapshot(attrs.permissions(), attrs.owner());
            } catch (UnsupportedOperationException | IOException e) {
                return null;
            }
        }

        void apply(Path path) {
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            if (permissions != null) {
                try {
                    view.setPermissions(permissions);
                } catch (UnsupportedOperationException | IOException ignored) {
                    // The rewritten bytes stay. Mode restore is best-effort.
                }
            }
            if (owner != null) {
                try {
                    view.setOwner(owner);
                } catch (UnsupportedOperationException | IOException ignored) {
                    // Owner restore needs privilege.
                }
            }
        }
    }
}
