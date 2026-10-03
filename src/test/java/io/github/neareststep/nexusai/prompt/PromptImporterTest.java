package io.github.neareststep.nexusai.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptImporterTest {

    @Test
    void addsNewIdsSkipsInvalidAndKeepsConflicts(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), """
                config-version: 1
                # keep this comment
                existing: "already here"
                """, StandardCharsets.UTF_8);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), """
                fresh: "a new prompt"
                existing: "replacement"
                Bad Id: "nope"
                empty: ""
                """);

        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        assertTrue(report.changed());
        assertEquals(java.util.List.of("fresh"), report.added());
        assertTrue(report.conflicting().contains("existing"));
        assertTrue(report.skipped().contains("existing"));
        assertTrue(report.skipped().contains("Bad Id") || report.warnings().toString().contains("Bad Id"));
        assertTrue(report.backup() != null && Files.isRegularFile(report.backup()));
        assertTrue(Files.readString(report.backup()).contains("already here"));

        String prompts = Files.readString(dir.resolve("prompts.yml"));
        assertTrue(prompts.contains("# keep this comment"));
        PromptCatalog catalog = PromptCatalog.parse(prompts).catalog();
        assertEquals("a new prompt", catalog.find("fresh").orElseThrow().template());
        assertEquals("already here", catalog.find("existing").orElseThrow().template());
        assertFalse(catalog.find("Bad Id").isPresent());
    }

    @Test
    void overwriteReplacesConflictingIdsAfterABackup(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "existing: \"old\"\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("prompts.yml.bak"), "previous backup\n", StandardCharsets.UTF_8);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), "existing: \"new\"\n");

        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", true);
        assertTrue(report.success(), report.error());
        assertTrue(report.conflicting().contains("existing"));
        assertFalse(report.skipped().contains("existing"));
        assertTrue(report.backup().getFileName().toString().startsWith("prompts.yml.bak."));
        assertEquals("new", PromptCatalog.parse(Files.readString(dir.resolve("prompts.yml")))
                .catalog().find("existing").orElseThrow().template());
        assertEquals("previous backup\n", Files.readString(dir.resolve("prompts.yml.bak")));
    }

    @Test
    void pathsCannotEscapeTheImportFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "safe: \"stay\"\n");
        Files.createDirectories(dir.resolve("import"));
        Files.writeString(dir.resolve("secret.yml"), "secret: \"no\"\n");

        assertFalse(PromptImporter.importFile(dir, "../secret.yml", false).success());
        assertFalse(PromptImporter.importFile(dir, "/etc/passwd.yml", false).success());
        assertFalse(PromptImporter.importFile(dir, "pack/../../secret.yml", true).success());
        assertEquals("stay", PromptCatalog.parse(Files.readString(dir.resolve("prompts.yml")))
                .catalog().find("safe").orElseThrow().template());
        assertFalse(Files.exists(dir.resolve("prompts.yml.bak")));
    }

    @Test
    void invalidImportYamlDoesNotWrite(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "safe: \"stay\"\n");
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("broken.yml"), "prompts: [\n");
        PromptImporter.Report report = PromptImporter.importFile(dir, "broken.yml", true);
        assertFalse(report.success());
        assertEquals("stay", PromptCatalog.parse(Files.readString(dir.resolve("prompts.yml")))
                .catalog().find("safe").orElseThrow().template());
        assertFalse(Files.exists(dir.resolve("prompts.yml.bak")));
    }

    @Test
    void importedContextKeyIsKept(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("prompts.yml"), "config-version: 1\n", StandardCharsets.UTF_8);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), """
                shop_tip:
                  prompt: "Give the player one short shopping tip."
                  context: [economy, rank]
                """);
        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        PromptCatalog.Parsed parsed = PromptCatalog.parse(Files.readString(dir.resolve("prompts.yml")));
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals(java.util.List.of("economy", "rank"),
                parsed.catalog().find("shop_tip").orElseThrow().context().ids());
    }

    @Test
    void importCreatesAMissingPromptsFileAsOwnerReadWrite(@TempDir Path dir) throws Exception {
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), "fresh: \"hello\"\n");
        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        Path prompts = dir.resolve("prompts.yml");
        assertEquals("hello", PromptCatalog.parse(Files.readString(prompts)).catalog().find("fresh").orElseThrow().template());
        PosixFileAttributeView view = Files.getFileAttributeView(prompts, PosixFileAttributeView.class);
        if (view != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), view.readAttributes().permissions());
        }
    }

    @Test
    void importKeepsTheModeOfAnExistingPromptsFile(@TempDir Path dir) throws Exception {
        Path prompts = dir.resolve("prompts.yml");
        Files.writeString(prompts, "old: \"stay\"\n");
        PosixFileAttributeView view = Files.getFileAttributeView(prompts, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        Set<PosixFilePermission> mode = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(prompts, mode);
        Path imports = Files.createDirectories(dir.resolve("import"));
        Files.writeString(imports.resolve("pack.yml"), "fresh: \"hello\"\n");
        PromptImporter.Report report = PromptImporter.importFile(dir, "pack.yml", false);
        assertTrue(report.success(), report.error());
        assertEquals(mode, Files.getPosixFilePermissions(prompts));
        assertEquals("stay", PromptCatalog.parse(Files.readString(prompts)).catalog().find("old").orElseThrow().template());
    }
}
