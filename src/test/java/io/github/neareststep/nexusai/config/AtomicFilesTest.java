package io.github.neareststep.nexusai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @Test
    void newConfigAndPromptsAreOwnerReadWriteAndExistingFilesKeepTheirMode(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.yml");
        Path prompts = dir.resolve("prompts.yml");
        AtomicFiles.installPrivate(config, bytes("api:\n  key: secret\n"));
        AtomicFiles.installPrivate(prompts, bytes("fresh: \"hello\"\n"));
        assertTrue(Files.readString(config).contains("secret"));
        assertTrue(Files.readString(prompts).contains("hello"));
        PosixFileAttributeView configView = Files.getFileAttributeView(config, PosixFileAttributeView.class);
        if (configView != null) {
            assertEquals(OWNER_ONLY, configView.readAttributes().permissions());
            assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(prompts));
        }

        Set<PosixFilePermission> shared = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        if (configView != null) {
            Files.setPosixFilePermissions(config, shared);
        }
        Files.writeString(config, "keep-me\n");
        if (configView != null) {
            Files.setPosixFilePermissions(config, shared);
        }
        AtomicFiles.installPrivate(config, bytes("replaced\n"));
        assertEquals("keep-me\n", Files.readString(config));
        if (configView != null) {
            assertEquals(shared, Files.getPosixFilePermissions(config));
        }
        assertFalse(Files.readString(config).contains("replaced"));
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
