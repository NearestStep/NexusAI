package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemoryStoreTest {

    @Test
    void summaryRoundTripAndLegacyFileLoads() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-memory");
        Path file = dir.resolve("dialogue-memory.yml");
        String legacy = """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: hello
                      - role: assistant
                        text: hi
                """.formatted(player);
        Files.writeString(file, legacy, StandardCharsets.UTF_8);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, null);
        assertEquals(2, loaded.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).size());
        assertEquals("", loaded.summary(player, "blacksmith", 60L, 10_000L));

        loaded.completeSummary(player, "blacksmith", "They said hello.", 70L, loaded.get(player, "blacksmith").epoch());
        loaded.save(file.toFile(), null, true);

        assertTrue(Files.exists(dir.resolve("dialogue-memory.yml.bak")));
        assertEquals(legacy, Files.readString(dir.resolve("dialogue-memory.yml.bak"), StandardCharsets.UTF_8));
        String upgraded = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(upgraded.contains("format:"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(2, yaml.getInt("format"));
        assertEquals("They said hello.", yaml.getString("entries." + player + ".blacksmith.summary"));
        assertEquals(70L, yaml.getLong("entries." + player + ".blacksmith.summary-updated"));
        assertFalse(upgraded.contains("pending"));

        MemoryStore again = new MemoryStore();
        again.load(file.toFile(), 80L, 10_000L, null);
        assertEquals("They said hello.", again.summary(player, "blacksmith", 80L, 10_000L));
        assertEquals(70L, again.get(player, "blacksmith").summaryUpdatedAt());

        again.save(file.toFile(), null, true);
        long bakFiles = Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count();
        assertEquals(1L, bakFiles);
    }

    @Test
    void disabledSaveMatchesTheLegacyDocument() throws Exception {
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "blacksmith", "user", "hello", 50L, 8, 100, 0L);
        store.append(player, "blacksmith", "assistant", "hi", 51L, 8, 100, 0L);
        store.get(player, "blacksmith").completeSummary("hidden", 52L, store.get(player, "blacksmith").epoch());

        Path dir = Files.createTempDirectory("dialogue-off");
        Path file = dir.resolve("dialogue-memory.yml");
        store.save(file.toFile(), null, false);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("summary"));
        assertFalse(text.contains("format"));
        assertFalse(Files.exists(dir.resolve("dialogue-memory.yml.bak")));

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(51L, yaml.getLong("entries." + player + ".blacksmith.updated"));
        assertEquals("hello", yaml.getMapList("entries." + player + ".blacksmith.lines").get(0).get("text"));
        assertEquals("hi", yaml.getMapList("entries." + player + ".blacksmith.lines").get(1).get("text"));
        assertFalse(yaml.contains("entries." + player + ".blacksmith.summary"));
    }

    @Test
    void interruptedSaveLeavesThePreviousFileIntact() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-kill");
        Path file = dir.resolve("dialogue-memory.yml");
        String original = "entries:\n  " + player + ":\n    blacksmith:\n      updated: 1\n      lines:\n      - {role: user, text: safe}\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        MemoryStore store = new MemoryStore();
        store.append(player, "blacksmith", "user", "new", 9L, 8, 100, 0L);
        store.save(file.toFile(), null, true, (temporary, target) -> {
            throw new IOException("killed");
        });

        assertArrayEquals(before, Files.readAllBytes(file));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("safe", yaml.getMapList("entries." + player + ".blacksmith.lines").get(0).get("text"));
        assertFalse(yaml.contains("format"));
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
    }

    @Test
    void newMemoryFileIsOwnerReadWriteAndAnExistingModeIsKept() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-mode");
        assumePosix(dir);
        Set<PosixFilePermission> ownerOnly = Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        MemoryStore created = new MemoryStore();
        UUID player = UUID.randomUUID();
        created.append(player, "blacksmith", "user", "hello", 50L, 8, 100, 0L);
        Path fresh = dir.resolve("dialogue-memory.yml");
        created.save(fresh.toFile(), null, false);
        assertEquals(ownerOnly, Files.getPosixFilePermissions(fresh));

        Path existing = dir.resolve("kept.yml");
        Files.writeString(existing, "entries: {}\n", StandardCharsets.UTF_8);
        Set<PosixFilePermission> mode = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(existing, mode);
        UserPrincipal owner = Files.getOwner(existing);
        MemoryStore again = new MemoryStore();
        again.append(player, "blacksmith", "user", "again", 60L, 8, 100, 0L);
        again.save(existing.toFile(), null, false);
        assertEquals(mode, Files.getPosixFilePermissions(existing));
        assertEquals(owner, Files.getOwner(existing));
        assertTrue(Files.readString(existing).contains("again"));

        Path locked = dir.resolve("locked.yml");
        Files.writeString(locked, "entries: {}\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(locked, ownerOnly);
        again.save(locked.toFile(), null, false);
        assertEquals(ownerOnly, Files.getPosixFilePermissions(locked));
    }

    @Test
    void anOldFormat2FileIsMaskedOnLoadAndKeepsTheSummary() throws Exception {
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String configured = "qa-ring-kA-0002y";
        String vendor = "sk-qaUnconfigured9999zz";
        Path dir = Files.createTempDirectory("dialogue-migrate");
        Path file = dir.resolve("dialogue-memory.yml");
        String old = """
                format: 2
                entries:
                  %s:
                    blacksmith:
                      updated: 1000
                      summary: They mentioned %s once.
                      summary-updated: 900
                      lines:
                      - role: user
                        text: the code is %s
                      - role: assistant
                        text: noted
                    scribe:
                      updated: 1000
                      summary: Token %s was spoken.
                      summary-updated: 800
                      lines:
                      - role: user
                        text: hello
                    quiet:
                      updated: 1
                      summary: expired %s
                      summary-updated: 1
                      lines:
                      - role: user
                        text: old %s
                """.formatted(player, configured, configured, vendor, configured, configured);
        Files.writeString(file, old, StandardCharsets.UTF_8);
        List<String> logged = new ArrayList<>();
        Logger logger = capturingLogger("memory-migrate", logged);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 1_200L, 500L, logger, List.of(configured));

        assertEquals("They mentioned ****002y once.", loaded.summary(player, "blacksmith", 1_200L, 500L));
        assertEquals(900L, loaded.get(player, "blacksmith").summaryUpdatedAt());
        assertEquals("the code is ****002y", loaded.transcript(player, "blacksmith", 1_200L, 8, 100, 500L).get(0).text());
        assertEquals("noted", loaded.transcript(player, "blacksmith", 1_200L, 8, 100, 500L).get(1).text());
        assertEquals("Token ****99zz was spoken.", loaded.summary(player, "scribe", 1_200L, 500L));
        assertEquals(800L, loaded.get(player, "scribe").summaryUpdatedAt());
        assertTrue(loaded.transcript(player, "quiet", 1_200L, 8, 100, 500L).isEmpty());

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains(configured), rewritten);
        assertFalse(rewritten.contains(vendor), rewritten);
        assertTrue(rewritten.contains("****002y"), rewritten);
        assertTrue(rewritten.contains("****99zz"), rewritten);
        assertTrue(rewritten.contains("summary-updated"), rewritten);
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count());
        assertTrue(logged.stream().anyMatch(line -> line.equals("Masked API keys in dialogue-memory.yml")));
        assertTrue(logged.stream().noneMatch(line -> line.contains(configured) || line.contains(vendor)));

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(2, yaml.getInt("format"));
        assertEquals("expired ****002y", yaml.getString("entries." + player + ".quiet.summary"));
        assertEquals("old ****002y", yaml.getMapList("entries." + player + ".quiet.lines").get(0).get("text"));
        assertEquals(1L, yaml.getLong("entries." + player + ".quiet.updated"));

        byte[] after = Files.readAllBytes(file);
        logged.clear();
        MemoryStore again = new MemoryStore();
        again.load(file.toFile(), 1_200L, 500L, logger, List.of(configured));
        assertArrayEquals(after, Files.readAllBytes(file));
        assertEquals("They mentioned ****002y once.", again.summary(player, "blacksmith", 1_200L, 500L));
        assertTrue(logged.stream().noneMatch(line -> line.contains("Masked API keys")));
    }

    @Test
    void aLegacyLineIsMaskedWithoutABackupOrAFormatKey() throws Exception {
        UUID player = UUID.randomUUID();
        String configured = "qa-ring-kA-0002y";
        Path dir = Files.createTempDirectory("dialogue-legacy-mask");
        Path file = dir.resolve("dialogue-memory.yml");
        String legacy = """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: hello %s
                """.formatted(player, configured);
        Files.writeString(file, legacy, StandardCharsets.UTF_8);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, null, List.of(configured));
        assertEquals("hello ****002y", loaded.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).get(0).text());

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains(configured), rewritten);
        assertFalse(rewritten.contains("format"), rewritten);
        assertFalse(Files.exists(dir.resolve("dialogue-memory.yml.bak")));
        assertTrue(rewritten.contains("****002y"), rewritten);
    }

    @Test
    void aFileWithoutKeysIsLeftByteForByte() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-clean");
        Path file = dir.resolve("dialogue-memory.yml");
        String clean = """
                format: 2
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      summary: The smith asked about the sky and the task-list.
                      summary-updated: 40
                      lines:
                      - role: user
                        text: sk-iron stays in the chest. sk-abcdefghijklmnopqrst
                """.formatted(player);
        Files.writeString(file, clean, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, null, List.of("qa-ring-kA-0002y"));

        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals("The smith asked about the sky and the task-list.",
                loaded.summary(player, "blacksmith", 60L, 10_000L));
        assertTrue(loaded.transcript(player, "blacksmith", 60L, 8, 200, 10_000L).get(0).text()
                .contains("sk-abcdefghijklmnopqrst"));
    }

    @Test
    void redactOnDiskMasksAKeyWithoutLoadingIt() throws Exception {
        UUID player = UUID.randomUUID();
        String configured = "qa-ring-kA-0002y";
        Path dir = Files.createTempDirectory("dialogue-disk-only");
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, """
                format: 2
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      summary: kept %s
                      summary-updated: 40
                      lines:
                      - role: user
                        text: hello
                """.formatted(player, configured), StandardCharsets.UTF_8);

        MemoryStore live = new MemoryStore();
        MemoryStore.redactOnDisk(file.toFile(), List.of(configured), null);

        assertTrue(live.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).isEmpty());
        assertEquals("", live.summary(player, "blacksmith", 60L, 10_000L));
        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains(configured), rewritten);
        assertTrue(rewritten.contains("kept ****002y"), rewritten);
        assertTrue(rewritten.contains("hello"), rewritten);
        assertFalse(Files.exists(dir.resolve("dialogue-memory.yml.bak")));
    }

    @Test
    void aCorruptFileIsPreservedAndAutosaveDoesNotReplaceItWhenSummariesAreOff() throws Exception {
        corruptFileSurvivesSave(false);
    }

    @Test
    void aCorruptFileIsPreservedAndAutosaveDoesNotReplaceItWhenSummariesAreOn() throws Exception {
        corruptFileSurvivesSave(true);
    }

    @Test
    void anExistingCorruptCopyIsNotReplaced() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-keep");
        Path file = dir.resolve("dialogue-memory.yml");
        Path kept = dir.resolve("dialogue-memory.yml.corrupt");
        Files.writeString(kept, "sentinel-keep\n", StandardCharsets.UTF_8);
        Files.writeString(file, brokenMemory("qa-ring-kA-0002y"), StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), 60L, 10_000L, null, List.of("qa-ring-kA-0002y"));
        assertEquals("sentinel-keep\n", Files.readString(kept, StandardCharsets.UTF_8));
        assertEquals(1, Files.list(dir).filter(path -> path.getFileName().toString().startsWith("dialogue-memory.yml.corrupt.")).count());
        String newer = Files.list(dir)
                .filter(path -> path.getFileName().toString().startsWith("dialogue-memory.yml.corrupt."))
                .map(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .findFirst()
                .orElse("");
        assertFalse(newer.contains("qa-ring-kA-0002y"), newer);
        assertTrue(newer.contains("****002y"), newer);
    }

    @Test
    void staleTempFilesAreRemovedOnLoadAndOtherFilesStay() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-stale-tmp");
        Path file = dir.resolve("dialogue-memory.yml");
        String clean = """
                format: 2
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: hello
                """.formatted(player);
        Files.writeString(file, clean, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        Path stale = dir.resolve("dialogue-memory.yml." + UUID.randomUUID() + ".tmp");
        Files.writeString(stale, "qa-ring-kA-0002y", StandardCharsets.UTF_8);
        Path empty = dir.resolve("dialogue-memory.yml." + UUID.randomUUID() + ".tmp");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        Path bak = dir.resolve("dialogue-memory.yml.bak");
        Files.writeString(bak, "keep-bak\n", StandardCharsets.UTF_8);
        Path other = dir.resolve("notes.tmp");
        Files.writeString(other, "keep-notes\n", StandardCharsets.UTF_8);
        List<String> logged = new ArrayList<>();

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, capturingLogger("stale-tmp", logged), List.of());

        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(empty));
        assertEquals("keep-bak\n", Files.readString(bak, StandardCharsets.UTF_8));
        assertEquals("keep-notes\n", Files.readString(other, StandardCharsets.UTF_8));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(logged.stream().anyMatch(line -> line.contains("Removed 2 stale")));
        assertTrue(logged.stream().noneMatch(line -> line.contains("qa-ring-kA-0002y")));
    }

    @Test
    void maskingRewriteIsOwnerReadWrite() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-mask-mode");
        assumePosix(dir);
        UUID player = UUID.randomUUID();
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, """
                format: 2
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      summary: hello qa-ring-kA-0002y
                      summary-updated: 40
                      lines:
                      - role: user
                        text: hi
                """.formatted(player), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, null, List.of("qa-ring-kA-0002y"));

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        assertFalse(Files.readString(file).contains("qa-ring-kA-0002y"));
        assertTrue(Files.readString(file).contains("****002y"));
    }

    private static void corruptFileSurvivesSave(boolean summaries) throws Exception {
        String configured = "qa-ring-kA-0002y";
        String vendor = "sk-proj-AbCdEfGhIjKlMn9pQrStUv";
        Path dir = Files.createTempDirectory("dialogue-corrupt-" + summaries);
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, brokenMemory(configured + " " + vendor), StandardCharsets.UTF_8);
        List<String> logged = new ArrayList<>();
        Logger logger = capturingLogger("corrupt-" + summaries, logged);

        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), 60L, 10_000L, logger, List.of(configured));

        assertFalse(Files.exists(file));
        Path copy = dir.resolve("dialogue-memory.yml.corrupt");
        assertTrue(Files.exists(copy));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(copy));
        String preserved = Files.readString(copy, StandardCharsets.UTF_8);
        assertTrue(preserved.contains("blacksmith"), preserved);
        assertTrue(preserved.contains("****002y"), preserved);
        assertTrue(preserved.contains("****StUv"), preserved);
        assertFalse(preserved.contains(configured), preserved);
        assertFalse(preserved.contains(vendor), preserved);
        assertTrue(preserved.contains("\t"), preserved);
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count());
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        String joined = String.join("\n", logged);
        assertTrue(joined.contains("dialogue-memory.yml was not loaded"), joined);
        assertTrue(joined.contains("dialogue-memory.yml.corrupt"), joined);
        assertTrue(joined.contains("Repair"), joined);
        assertFalse(joined.contains(configured), joined);
        assertFalse(joined.contains(vendor), joined);

        byte[] preservedBytes = Files.readAllBytes(copy);
        UUID player = UUID.randomUUID();
        store.append(player, "blacksmith", "user", "after", 70L, 8, 100, 0L);
        store.save(file.toFile(), logger, summaries);

        assertArrayEquals(preservedBytes, Files.readAllBytes(copy));
        assertTrue(Files.exists(file));
        String fresh = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(fresh.contains("after"), fresh);
        assertFalse(fresh.contains(configured), fresh);
        assertFalse(fresh.contains(vendor), fresh);
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count());
        if (summaries) {
            assertTrue(fresh.contains("format"), fresh);
        }
    }

    @Test
    void anInterruptedCorruptCopyLeavesNoPartialFileAndKeepsTheOriginal() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-kill");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        String original = brokenMemory("qa-ring-kA-0002y");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        byte[] before = Files.readAllBytes(file);
        List<String> logged = new ArrayList<>();
        MemoryStore.CorruptMove previous = MemoryStore.corruptMove;
        MemoryStore.corruptMove = (temporary, destination) -> {
            byte[] staged = Files.readAllBytes(temporary);
            assertTrue(staged.length > 0);
            assertFalse(new String(staged, StandardCharsets.UTF_8).contains("qa-ring-kA-0002y"));
            assertTrue(new String(staged, StandardCharsets.UTF_8).contains("****002y"));
            assertFalse(Files.exists(destination));
            throw new IOException("killed qa-ring-kA-0002y");
        };
        try {
            MemoryStore store = new MemoryStore();
            store.load(file.toFile(), 60L, 10_000L, capturingLogger("corrupt-kill", logged), List.of("qa-ring-kA-0002y"));
            assertFalse(Files.exists(dir.resolve("dialogue-memory.yml.corrupt")));
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".corrupt")).count());
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
            store.append(UUID.randomUUID(), "blacksmith", "user", "after", 70L, 8, 100, 0L);
            store.save(file.toFile(), capturingLogger("corrupt-kill-save", logged), false);
            assertArrayEquals(before, Files.readAllBytes(file));
            String joined = String.join("\n", logged);
            assertTrue(joined.contains("will not be overwritten"), joined);
            assertTrue(joined.contains("owner-only"), joined);
            assertTrue(joined.contains("IOException:"), joined);
            assertTrue(joined.contains("****002y"), joined);
            assertFalse(joined.contains("qa-ring-kA-0002y"), joined);
        } finally {
            MemoryStore.corruptMove = previous;
        }
    }

    @Test
    void aCorruptCopyThatCannotBeWrittenRestrictsTheOriginal() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-ro");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        String original = brokenMemory("qa-ring-kA-0002y");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        byte[] before = Files.readAllBytes(file);
        Files.setPosixFilePermissions(dir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        List<String> logged = new ArrayList<>();
        try {
            MemoryStore store = new MemoryStore();
            store.load(file.toFile(), 60L, 10_000L, capturingLogger("corrupt-ro", logged), List.of("qa-ring-kA-0002y"));
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".corrupt")).count());
            store.save(file.toFile(), capturingLogger("corrupt-ro-save", logged), true);
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count());
            String joined = String.join("\n", logged);
            assertTrue(joined.contains("could not be copied aside"), joined);
            assertTrue(joined.contains("AccessDeniedException:"), joined);
            assertTrue(joined.contains("Refusing to overwrite"), joined);
            assertFalse(joined.contains("qa-ring-kA-0002y"), joined);
        } finally {
            Files.setPosixFilePermissions(dir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void aNonUtf8CorruptFileIsCopiedAsideWithAsciiKeysMasked() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-bytes");
        assumePosix(dir);
        String configured = "qa-ring-kA-0002y";
        byte[] broken = ("entries:\n\tblacksmith:\n    summary: hello " + configured + " ")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[broken.length + 1];
        System.arraycopy(broken, 0, raw, 0, broken.length);
        raw[raw.length - 1] = (byte) 0xFF;
        Path file = dir.resolve("dialogue-memory.yml");
        Files.write(file, raw);
        List<String> logged = new ArrayList<>();

        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), 60L, 10_000L, capturingLogger("corrupt-bytes", logged), List.of(configured));

        assertFalse(Files.exists(file));
        Path copy = dir.resolve("dialogue-memory.yml.corrupt");
        byte[] preserved = Files.readAllBytes(copy);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(copy));
        String text = new String(preserved, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(configured), text);
        assertTrue(text.contains("****002y"), text);
        assertTrue(text.contains("blacksmith"), text);
        assertEquals((byte) 0xFF, preserved[preserved.length - 1]);
        String joined = String.join("\n", logged);
        assertTrue(joined.contains("not valid UTF-8"), joined);
        assertTrue(joined.contains("dialogue-memory.yml.corrupt"), joined);
        assertFalse(joined.contains(configured), joined);

        byte[] preservedBytes = Files.readAllBytes(copy);
        store.append(UUID.randomUUID(), "blacksmith", "user", "after", 70L, 8, 100, 0L);
        store.save(file.toFile(), null, false);
        assertArrayEquals(preservedBytes, Files.readAllBytes(copy));
        assertTrue(Files.readString(file).contains("after"));
    }

    @Test
    void aFailedCopyOnTheLiveStoreStillRefusesAfterPersistenceIsEnabled() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-live");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, brokenMemory("qa-ring-kA-0002y"), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        byte[] before = Files.readAllBytes(file);
        Files.setPosixFilePermissions(dir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        List<String> logged = new ArrayList<>();
        try {
            MemoryStore store = new MemoryStore();
            MemoryStore.redactOnDisk(file.toFile(), List.of("qa-ring-kA-0002y"),
                    capturingLogger("live-ro", logged), store);
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
            String joined = String.join("\n", logged);
            assertTrue(joined.contains("AccessDeniedException:"), joined);
            assertFalse(joined.contains("qa-ring-kA-0002y"), joined);

            Files.setPosixFilePermissions(dir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            assertFalse(store.loadForPersistence(file.toFile(), 60L, 10_000L,
                    capturingLogger("live-load", logged), List.of("qa-ring-kA-0002y")));
            assertArrayEquals(before, Files.readAllBytes(file));
            store.append(UUID.randomUUID(), "blacksmith", "user", "after", 70L, 8, 100, 0L);
            store.save(file.toFile(), capturingLogger("live-save", logged), true);
            assertArrayEquals(before, Files.readAllBytes(file));
            joined = String.join("\n", logged);
            assertTrue(joined.contains("Refusing to overwrite"), joined);
            assertFalse(joined.contains("qa-ring-kA-0002y"), joined);
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".corrupt")).count());
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count());
        } finally {
            Files.setPosixFilePermissions(dir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    @Test
    void enablingPersistenceLoadsTheFileInsteadOfSavingEmptyMemory() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-enable-persist");
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: kept-from-disk
                """.formatted(player), StandardCharsets.UTF_8);

        Path wiped = dir.resolve("wiped.yml");
        Files.copy(file, wiped);
        MemoryStore empty = new MemoryStore();
        empty.save(wiped.toFile(), null, false);
        assertFalse(Files.readString(wiped).contains("kept-from-disk"));

        MemoryStore store = new MemoryStore();
        MemoryStore.redactOnDisk(file.toFile(), List.of(), null, store);
        assertTrue(store.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).isEmpty());
        assertTrue(store.loadForPersistence(file.toFile(), 60L, 10_000L, null, List.of()));
        assertEquals("kept-from-disk",
                store.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).get(0).text());
        store.append(player, "blacksmith", "user", "after-reload", 70L, 8, 100, 0L);
        store.save(file.toFile(), null, false);
        String saved = Files.readString(file);
        assertTrue(saved.contains("kept-from-disk"), saved);
        assertTrue(saved.contains("after-reload"), saved);
    }

    @Test
    void aSaveStartedDuringLoadDoesNotWriteAnEmptyFile() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-load-race");
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: kept-from-disk
                """.formatted(player), StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        CountDownLatch insideLoad = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        CountDownLatch saveFinished = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = () -> {
            insideLoad.countDown();
            try {
                if (!releaseLoad.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("load was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        Thread loader = new Thread(() -> store.loadForPersistence(file.toFile(), 60L, 10_000L, null, List.of()),
                "memory-load");
        Thread saver = new Thread(() -> {
            store.save(file.toFile(), null, false);
            saveFinished.countDown();
        }, "memory-save");
        try {
            loader.start();
            assertTrue(insideLoad.await(5, TimeUnit.SECONDS));
            assertTrue(store.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).isEmpty());
            saver.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!store.diskLock.hasQueuedThread(saver)) {
                if (saveFinished.getCount() == 0) {
                    fail("save wrote before the load released the lock");
                }
                if (System.nanoTime() > deadline) {
                    fail("save did not wait for the load");
                }
                Thread.onSpinWait();
            }
            assertTrue(Files.readString(file).contains("kept-from-disk"));
            releaseLoad.countDown();
            assertTrue(saveFinished.await(5, TimeUnit.SECONDS));
            loader.join(5_000L);
            assertEquals("kept-from-disk",
                    store.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).get(0).text());
            String saved = Files.readString(file);
            assertTrue(saved.contains("kept-from-disk"), saved);
        } finally {
            MemoryStore.pauseDuringLoad = null;
            releaseLoad.countDown();
            loader.join(5_000L);
            saver.join(5_000L);
        }
    }

    @Test
    void autosaveForcesTheTempFileBeforeRenameAndKeepsGoingWhenFsyncFails() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-fsync");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, "entries: {}\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));
        List<String> trace = new ArrayList<>();
        MemoryStore.DiskSync previous = MemoryStore.diskSync;
        MemoryStore.durableTrace = trace;
        MemoryStore.diskSync = path -> {
            if (Files.isRegularFile(path)) {
                String text = Files.readString(path);
                assertTrue(text.contains("kept-line"), text);
            }
            throw new IOException("fsync failed qa-ring-kA-0002y");
        };
        List<String> logged = new ArrayList<>();
        try {
            MemoryStore store = new MemoryStore();
            store.secrets(() -> List.of("qa-ring-kA-0002y"));
            store.append(UUID.randomUUID(), "blacksmith", "user", "kept-line", 70L, 8, 100, 0L);
            store.save(file.toFile(), capturingLogger("fsync-fail", logged), false);
            assertEquals(List.of("force-temp", "rename", "force-dir"), trace);
            String saved = Files.readString(file);
            assertTrue(saved.contains("kept-line"), saved);
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file);
            assertTrue(mode.contains(PosixFilePermission.OWNER_READ));
            assertTrue(mode.contains(PosixFilePermission.OWNER_WRITE));
            assertTrue(mode.contains(PosixFilePermission.GROUP_READ), mode.toString());
            assertTrue(logged.stream().noneMatch(line -> line.contains("Failed to save")), logged.toString());
            assertTrue(logged.stream().noneMatch(line -> line.contains("qa-ring-kA-0002y")), logged.toString());
        } finally {
            MemoryStore.diskSync = previous;
            MemoryStore.durableTrace = null;
        }
    }

    @Test
    void aNewAutosaveIsOwnerReadWriteWhenFsyncFails() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-fsync-new");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        MemoryStore.DiskSync previous = MemoryStore.diskSync;
        MemoryStore.diskSync = path -> {
            throw new IOException("fsync failed");
        };
        try {
            MemoryStore store = new MemoryStore();
            store.append(UUID.randomUUID(), "blacksmith", "user", "fresh", 70L, 8, 100, 0L);
            store.save(file.toFile(), null, false);
            assertTrue(Files.readString(file).contains("fresh"));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        } finally {
            MemoryStore.diskSync = previous;
        }
    }

    @Test
    void maskingRewriteForcesTheTempFileBeforeRename() throws Exception {
        Path dir = Files.createTempDirectory("dialogue-fsync-mask");
        assumePosix(dir);
        UUID player = UUID.randomUUID();
        Path file = dir.resolve("dialogue-memory.yml");
        Files.writeString(file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: hello qa-ring-kA-0002y
                """.formatted(player), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ));
        List<String> trace = new ArrayList<>();
        MemoryStore.durableTrace = trace;
        try {
            MemoryStore store = new MemoryStore();
            store.load(file.toFile(), 60L, 10_000L, null, List.of("qa-ring-kA-0002y"));
            assertEquals(List.of("force-temp", "rename", "force-dir"), trace);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(file));
            String text = Files.readString(file);
            assertFalse(text.contains("qa-ring-kA-0002y"), text);
            assertTrue(text.contains("****002y"), text);
            assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
        } finally {
            MemoryStore.durableTrace = null;
        }
    }

    @Test
    void anIdenticalCorruptCopyIsReusedAndTheOriginalIsRemoved() throws Exception {
        identicalCorruptIsReused("dialogue-memory.yml.corrupt");
    }

    @Test
    void anIdenticalStampedCorruptCopyIsReusedAndTheOriginalIsRemoved() throws Exception {
        identicalCorruptIsReused("dialogue-memory.yml.corrupt.1700000000000");
    }

    private static void identicalCorruptIsReused(String existingName) throws Exception {
        Path dir = Files.createTempDirectory("dialogue-corrupt-dup");
        assumePosix(dir);
        Path file = dir.resolve("dialogue-memory.yml");
        String original = brokenMemory("qa-ring-kA-0002y");
        Files.writeString(file, original, StandardCharsets.UTF_8);
        List<String> logged = new ArrayList<>();
        MemoryStore first = new MemoryStore();
        first.load(file.toFile(), 60L, 10_000L, capturingLogger("dup-first", logged), List.of("qa-ring-kA-0002y"));
        Path produced = dir.resolve("dialogue-memory.yml.corrupt");
        assertTrue(Files.exists(produced));
        byte[] masked = Files.readAllBytes(produced);
        Path existing = dir.resolve(existingName);
        if (!existing.equals(produced)) {
            Files.move(produced, existing);
        }
        Files.writeString(file, original, StandardCharsets.UTF_8);
        logged.clear();
        MemoryStore again = new MemoryStore();
        again.load(file.toFile(), 60L, 10_000L, capturingLogger("dup-second", logged), List.of("qa-ring-kA-0002y"));
        assertFalse(Files.exists(file));
        assertArrayEquals(masked, Files.readAllBytes(existing));
        assertEquals(1, Files.list(dir).filter(path -> path.getFileName().toString().contains(".corrupt")).count());
        String joined = String.join("\n", logged);
        assertTrue(joined.contains("already exists as " + existingName), joined);
        assertTrue(joined.contains("The original was removed"), joined);
        assertFalse(joined.contains("qa-ring-kA-0002y"), joined);
        again.append(UUID.randomUUID(), "blacksmith", "user", "after", 70L, 8, 100, 0L);
        again.save(file.toFile(), null, false);
        assertTrue(Files.readString(file).contains("after"));
        assertArrayEquals(masked, Files.readAllBytes(existing));
    }

    private static String brokenMemory(String secret) {
        return "entries:\n\tblacksmith:\n    summary: hello " + secret + "\n";
    }

    private static Logger capturingLogger(String name, List<String> lines) {
        Logger logger = Logger.getLogger(name + "-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static void assumePosix(Path dir) {
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null,
                "POSIX permissions are not available");
    }
}
