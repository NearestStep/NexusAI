package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * NAI-77, NAI-74, and NAI-75. The loader is substituted per case. The file on disk is not replaced
 * by a failed or hung read, and stop does not wait out a load that is still going.
 */
class DialogueMemoryReliabilityTest {

    private static final String SECRET = "sk-qaConfiguredKey1111";
    private static final String PAUSED = "Saves of dialogue-memory.yml are paused because the first load "
            + "did not finish. Restart the server to load the file and resume saves.";

    @AfterEach
    void resetLoaderHooks() {
        MemoryStore.pauseDuringLoad = null;
        DialogueMemoryPersistence.loaderThreads = DialogueMemoryPersistence.DEFAULT_LOADER_THREADS;
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 1_000L;
        MemoryStore.fullDocumentAppends.set(0);
    }

    @Test
    void runtimeExceptionInTheLoaderIsLoggedAndTheNextReloadLoads() throws Exception {
        Fixture fixture = fixture("runtime-load");
        byte[] before = Files.readAllBytes(fixture.file);
        MemoryStore.pauseDuringLoad = () -> {
            throw new IllegalStateException("load blew up " + SECRET + " " + fixture.file.toAbsolutePath());
        };
        try {
            fixture.files.onReload();
            awaitFinished(fixture.files);
            assertFalse(fixture.files.memoryLoadedFromDisk());
            assertNull(fixture.files.diskLoader());
            assertTrue(fixture.files.loadFinished());
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            assertEquals(1, warnings(fixture.records, "Failed to load dialogue-memory.yml").size());
            assertSecretsHidden(fixture.records);
            fixture.files.save();
            fixture.files.save();
            assertEquals(1, warnings(fixture.records, PAUSED).size());
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            long started = System.nanoTime();
            fixture.files.shutdown();
            assertTrue(millisSince(started) < 4_000L, "stop waited on a loader that had already failed");
            assertArrayEquals(before, Files.readAllBytes(fixture.file));

            MemoryStore.pauseDuringLoad = null;
            fixture.files.onReload();
            awaitFinished(fixture.files);
            assertTrue(fixture.files.memoryLoadedFromDisk());
            assertNull(fixture.files.diskLoader());
            assertEquals("kept-from-disk", line(fixture.store, fixture.player, "blacksmith"));
            fixture.files.save();
            String saved = Files.readString(fixture.file);
            assertTrue(saved.contains("kept-from-disk"), saved);
            assertSecretsHidden(fixture.records);
        } finally {
            MemoryStore.pauseDuringLoad = null;
        }
    }

    @Test
    void errorInTheLoaderDoesNotOverwriteTheFileAndTheNextReloadLoads() throws Exception {
        Fixture fixture = fixture("error-load");
        byte[] before = Files.readAllBytes(fixture.file);
        MemoryStore.pauseDuringLoad = () -> {
            throw new OutOfMemoryError(SECRET + " " + fixture.file.toAbsolutePath());
        };
        try {
            fixture.files.onReload();
            awaitFinished(fixture.files);
            assertFalse(fixture.files.memoryLoadedFromDisk());
            assertNull(fixture.files.diskLoader());
            assertTrue(fixture.files.loadFinished());
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> failed = warnings(fixture.records, "Failed to load dialogue-memory.yml");
            assertEquals(1, failed.size(), fixture.records.toString());
            assertTrue(failed.get(0).contains("OutOfMemoryError"), failed.get(0));
            assertSecretsHidden(fixture.records);
            fixture.files.save();
            fixture.files.save();
            assertEquals(1, warnings(fixture.records, PAUSED).size());
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));

            MemoryStore.pauseDuringLoad = null;
            fixture.files.onReload();
            awaitFinished(fixture.files);
            assertTrue(fixture.files.memoryLoadedFromDisk());
            assertEquals("kept-from-disk", line(fixture.store, fixture.player, "blacksmith"));
            assertSecretsHidden(fixture.records);
        } finally {
            MemoryStore.pauseDuringLoad = null;
        }
    }

    /**
     * NAI-77. {@code diskLoader = worker} happens before {@code worker.start()}. If {@code start}
     * throws, the thread never runs, so its {@code finally} cannot clear {@code diskLoader}.
     * The assertion message records that state when the assignment is left behind.
     */
    @Test
    void threadStartFailureDoesNotStickDiskLoaderAndTheNextReloadLoads() throws Exception {
        Fixture fixture = fixture("start-fail");
        byte[] before = Files.readAllBytes(fixture.file);
        List<Thread> created = new ArrayList<>();
        DialogueMemoryPersistence.loaderThreads = task -> {
            Thread worker = new Thread(task, "nexusai-memory-load") {
                @Override
                public synchronized void start() {
                    throw new IllegalThreadStateException("start failed " + SECRET + " " + fixture.file.toAbsolutePath());
                }
            };
            worker.setDaemon(true);
            created.add(worker);
            return worker;
        };
        Throwable thrown = null;
        try {
            fixture.files.onReload();
        } catch (Throwable error) {
            thrown = error;
        }
        assertEquals(1, created.size());
        assertEquals(Thread.State.NEW, created.get(0).getState(), "start() ran the loader body");
        assertNull(thrown, "diskLoader=" + fixture.files.diskLoader()
                + " alive=" + (fixture.files.diskLoader() != null && fixture.files.diskLoader().isAlive())
                + " state=" + created.get(0).getState()
                + " loaded=" + fixture.files.memoryLoadedFromDisk());
        assertNull(fixture.files.diskLoader());
        assertFalse(fixture.files.memoryLoadedFromDisk());
        assertTrue(fixture.files.loadFinished());
        assertArrayEquals(before, Files.readAllBytes(fixture.file));
        assertEquals(1, warnings(fixture.records, "Failed to load dialogue-memory.yml").size());
        assertSecretsHidden(fixture.records);
        fixture.files.save();
        fixture.files.save();
        assertEquals(1, warnings(fixture.records, PAUSED).size());
        assertFalse(warnings(fixture.records, PAUSED).get(0).contains(fixture.file.toString()),
                warnings(fixture.records, PAUSED).get(0));
        assertArrayEquals(before, Files.readAllBytes(fixture.file));
        long started = System.nanoTime();
        fixture.files.shutdown();
        assertTrue(millisSince(started) < 4_000L);
        assertArrayEquals(before, Files.readAllBytes(fixture.file));

        AtomicInteger second = new AtomicInteger();
        DialogueMemoryPersistence.loaderThreads = task -> {
            second.incrementAndGet();
            Thread worker = new Thread(task, "nexusai-memory-load");
            worker.setDaemon(true);
            return worker;
        };
        fixture.files.onReload();
        awaitFinished(fixture.files);
        assertEquals(1, second.get(), "the reload after a failed start did not load");
        assertTrue(fixture.files.memoryLoadedFromDisk());
        assertNull(fixture.files.diskLoader());
        assertEquals("kept-from-disk", line(fixture.store, fixture.player, "blacksmith"));
        assertSecretsHidden(fixture.records);
    }

    @Test
    void hungLoaderDoesNotOverwriteTheFileWarnsOnceAndStopReturns() throws Exception {
        Fixture fixture = fixture("hung-load");
        byte[] before = Files.readAllBytes(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = () -> {
            inside.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("hung load was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            assertFalse(fixture.files.memoryLoadedFromDisk());
            assertTrue(fixture.files.diskLoader() != null && fixture.files.diskLoader().isAlive());
            fixture.files.save();
            assertEquals(0, warnings(fixture.records, PAUSED).size(), fixture.records.toString());
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            fixture.clock.addAndGet(DialogueMemoryPersistence.LOAD_HANG_WARNING_AFTER_MILLIS);
            fixture.files.save();
            fixture.files.save();
            assertEquals(1, warnings(fixture.records, PAUSED).size(), fixture.records.toString());
            assertEquals(PAUSED, warnings(fixture.records, PAUSED).get(0));
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertTrue(elapsed < 4_000L, "stop took " + elapsed + "ms");
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            assertSecretsHidden(fixture.records);
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void unreadableFileOnTheFirstReloadWarnsOnceAndKeepsTheOriginal() throws Exception {
        Fixture fixture = fixture("mode-000");
        assumePosix(fixture.file.getParent());
        byte[] before = Files.readAllBytes(fixture.file);
        Files.setPosixFilePermissions(fixture.file, Set.of());
        try {
            fixture.files.onReload();
            awaitFinished(fixture.files);
            assertFalse(fixture.files.memoryLoadedFromDisk());
            assertNull(fixture.files.diskLoader());
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(fixture.file));
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            fixture.files.save();
            fixture.clock.addAndGet(65_000L);
            fixture.files.save();
            assertEquals(1, warnings(fixture.records, PAUSED).size(), fixture.records.toString());
            assertEquals(PAUSED, warnings(fixture.records, PAUSED).get(0));
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(fixture.file));
            assertSecretsHidden(fixture.records);
        } finally {
            if (Files.exists(fixture.file)) {
                Files.setPosixFilePermissions(fixture.file, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        }
    }

    @Test
    void stopDuringALongFirstLoadKeepsDiskCharactersAndAddsMemoryOnlyOnes() throws Exception {
        Fixture fixture = fixture("stop-during-load");
        assumePosix(fixture.file.getParent());
        UUID player = fixture.player;
        String withMiner = """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: kept-from-disk
                    miner:
                      updated: 40
                      lines:
                      - role: user
                        text: from-disk-only
                """.formatted(player);
        Files.writeString(fixture.file, withMiner, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fixture.file, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));
        fixture.store.append(player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = () -> {
            inside.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("long load was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertTrue(elapsed < 4_000L, "stop took " + elapsed + "ms");
            String merged = Files.readString(fixture.file);
            assertTrue(merged.contains("kept-from-disk"), merged);
            assertTrue(merged.contains("from-disk-only"), merged);
            assertTrue(merged.contains("only-in-memory"), merged);
            assertFalse(merged.contains("said-while-off"), merged);
            assertTrue(Files.getPosixFilePermissions(fixture.file).contains(PosixFilePermission.GROUP_READ));
            assertEquals(0, Files.list(fixture.file.getParent()).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
            byte[] afterShutdown = Files.readAllBytes(fixture.file);
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
                assertFalse(pending.isAlive());
            }
            assertArrayEquals(afterShutdown, Files.readAllBytes(fixture.file));
            assertEquals("kept-from-disk", line(fixture.store, player, "blacksmith"));
            assertEquals("only-in-memory", line(fixture.store, player, "innkeeper"));
            assertEquals("from-disk-only", line(fixture.store, player, "miner"));
            assertSecretsHidden(fixture.records);
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void stopDuringALargeFirstLoadStaysNearTheJoinGrace() throws Exception {
        Fixture fixture = fixture("stop-large");
        UUID player = fixture.player;
        String pad = "x".repeat(1100);
        StringBuilder body = new StringBuilder(12_000_000);
        body.append("entries:\n  ").append(player).append(":\n");
        for (int i = 0; i < 8_000; i++) {
            body.append("    npc_").append(i).append(":\n");
            body.append("      updated: 40\n");
            body.append("      lines:\n");
            body.append("      - role: user\n");
            body.append("        text: ").append(pad).append('\n');
        }
        String original = body.toString();
        Files.writeString(fixture.file, original, StandardCharsets.UTF_8);
        fixture.store.append(player, "npc_0", "user", "must-not-overwrite", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "npc_new", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = () -> {
            inside.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("large load was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertTrue(elapsed < 2_000L, "stop took " + elapsed + "ms");
            String merged = Files.readString(fixture.file);
            assertTrue(merged.startsWith(original), "existing characters were rewritten");
            assertTrue(merged.contains("only-in-memory"), merged.substring(Math.max(0, merged.length() - 500)));
            assertFalse(merged.contains("must-not-overwrite"), "a character already in the file was overwritten");
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
                assertFalse(pending.isAlive());
            }
            assertEquals(merged, Files.readString(fixture.file));
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void scanEventsReadsSavedCharacterKeys() throws Exception {
        String[] ids = {"blacksmith", "guide: north", "say \"hi\" and 'bye'", "кузнец"};
        for (boolean summaries : new boolean[] {false, true}) {
            Path file = Files.createTempFile("scan-keys-", ".yml");
            UUID player = UUID.randomUUID();
            MemoryStore writer = new MemoryStore();
            for (String id : ids) {
                writer.append(player, id, "user", "first line of " + id, 40L, 8, 8_000, 0L);
                writer.append(player, id, "assistant", "second line of " + id, 41L, 8, 8_000, 0L);
                writer.append(player, id, "user", "third line", 42L, 8, 8_000, 0L);
                if (summaries) {
                    var memory = writer.get(player, id);
                    memory.load(new ArrayList<>(memory.view()), memory.updatedAt(), "сводка " + id, memory.updatedAt());
                }
            }
            writer.save(file.toFile(), null, summaries);
            String text = Files.readString(file);
            Set<String> scanned = MemoryStore.scanCharacterKeys(text);
            assertTrue(scanned != null, summaries ? "summaries" : "plain");
            assertEquals(keysIn(text), scanned);
            assertEquals(ids.length, scanned.size());
        }
    }

    @Test
    void stopAfterASaveKeepsExistingLinesAndAddsMissingCharacters() throws Exception {
        stopAfterASave(false);
        stopAfterASave(true);
    }

    @Test
    void stopDuringALargeSavedLoadStaysNearTheJoinGrace() throws Exception {
        Fixture fixture = fixture("stop-large-saved");
        UUID player = fixture.player;
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa ".repeat(16);
        String second = "beta gamma delta epsilon zeta eta theta iota kappa lambda ".repeat(16);
        int count = 5_200;
        MemoryStore writer = new MemoryStore();
        for (int i = 0; i < count; i++) {
            writer.append(player, "npc_" + i, "user", text, 40L, 8, 8_000, 0L);
            writer.append(player, "npc_" + i, "assistant", second, 41L, 8, 8_000, 0L);
            writer.append(player, "npc_" + i, "user", "third line " + i, 42L, 8, 8_000, 0L);
        }
        writer.save(fixture.file.toFile(), null, false);
        long bytes = Files.size(fixture.file);
        assertTrue(bytes >= 11_000_000L && bytes <= 14_000_000L, "saved file is " + bytes + " bytes");
        String original = Files.readString(fixture.file);
        assertTrue(hasContinuation(original), "saved file has a colon on every content line");
        fixture.store.append(player, "npc_0", "user", "must-not-overwrite", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "npc_new", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "large saved load was not released");
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            MemoryStore.fullDocumentAppends.set(0);
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertEquals(0, MemoryStore.fullDocumentAppends.get(), "stop rewrote the file");
            assertTrue(elapsed < 2_500L, "stop took " + elapsed + "ms");
            String merged = Files.readString(fixture.file);
            assertOriginalLinesRemain(original, merged);
            YamlConfiguration parsed = yaml(merged);
            assertEquals(text, firstText(parsed, player, "npc_0"));
            assertEquals(3, parsed.getMapList("entries." + player + ".npc_0.lines").size());
            assertEquals("only-in-memory", firstText(parsed, player, "npc_new"));
            assertEquals(1, countOf(merged, "only-in-memory"));
            assertFalse(merged.contains("must-not-overwrite"));
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(15_000L);
                assertFalse(pending.isAlive());
            }
            assertEquals(merged, Files.readString(fixture.file));
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(15_000L);
            }
        }
    }

    @Test
    void stopNamesCharactersItCouldNotSave() throws Exception {
        Fixture fixture = fixture("stop-unread");
        byte[] before = "entries: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(fixture.file, before);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        fixture.store.append(fixture.player, "guide", "user", "also-only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "unreadable load was not released");
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 2 dialogue characters because dialogue-memory.yml could not be read.",
                    notes.get(0));
            assertEquals("only-in-memory", line(fixture.store, fixture.player, "innkeeper"));
            assertEquals("also-only-in-memory", line(fixture.store, fixture.player, "guide"));
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void stopStillAppendsWhenTheSavedShapeIsFlow() throws Exception {
        Fixture fixture = fixture("stop-flow");
        UUID player = fixture.player;
        String flow = "entries:\n  " + player + ":\n    shared: {updated: 40, lines: [{role: user, text: kept-flow}]}\n";
        Files.writeString(fixture.file, flow, StandardCharsets.UTF_8);
        fixture.store.append(player, "shared", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "flow load was not released");
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.files.shutdown();
            String merged = Files.readString(fixture.file);
            YamlConfiguration parsed = yaml(merged);
            assertEquals("kept-flow", firstText(parsed, player, "shared"));
            assertEquals("only-in-memory", firstText(parsed, player, "innkeeper"));
            assertFalse(merged.contains("said-while-off"), merged);
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
                assertFalse(pending.isAlive());
            }
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    private void stopAfterASave(boolean summaries) throws Exception {
        Fixture fixture = fixture(summaries ? "stop-saved-summary" : "stop-saved", summaries);
        UUID player = fixture.player;
        UUID other = UUID.randomUUID();
        String summary = summaries ? BLOCKED : "";
        MemoryStore writer = new MemoryStore();
        remember(writer, player, "bard", FOLDED, BLOCKED, summary, 50L);
        remember(writer, player, "shared", "disk-shared", null, "", 40L);
        remember(writer, player, "miner", "from-disk-only", null, "", 40L);
        writer.save(fixture.file.toFile(), null, summaries);
        String original = Files.readString(fixture.file);
        assertTrue(FOLDED.length() > 120, FOLDED);
        assertTrue(hasContinuation(original), original);
        fixture.store.append(player, "shared", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        fixture.store.append(other, "guide", "user", "new-player", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "saved load was not released");
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            MemoryStore.fullDocumentAppends.set(0);
            fixture.files.shutdown();
            assertEquals(0, MemoryStore.fullDocumentAppends.get(), "stop rewrote the file");
            String merged = Files.readString(fixture.file);
            yaml(merged);
            assertOriginalLinesRemain(original, merged);
            assertEquals(1, countOf(merged, "only-in-memory"));
            assertEquals(1, countOf(merged, "new-player"));
            assertParsedCharactersMatch(original, merged);
            YamlConfiguration parsed = yaml(merged);
            assertEquals("disk-shared", firstText(parsed, player, "shared"));
            assertEquals("only-in-memory", firstText(parsed, player, "innkeeper"));
            assertEquals("new-player", firstText(parsed, other, "guide"));
            assertEquals("from-disk-only", firstText(parsed, player, "miner"));
            assertFalse(merged.contains("said-while-off"), merged);
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
                assertFalse(pending.isAlive());
            }
            assertEquals(merged, Files.readString(fixture.file));
            assertEquals("disk-shared", line(fixture.store, player, "shared"));
            assertEquals("only-in-memory", line(fixture.store, player, "innkeeper"));
            assertEquals("new-player", line(fixture.store, other, "guide"));
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    private static void remember(
            MemoryStore store,
            UUID player,
            String character,
            String first,
            String second,
            String summary,
            long updated
    ) {
        List<TurnMemory.Line> lines = new ArrayList<>();
        lines.add(new TurnMemory.Line("user", first));
        if (second != null) {
            lines.add(new TurnMemory.Line("assistant", second));
        }
        store.get(player, character).load(lines, updated, summary, summary == null || summary.isBlank() ? 0L : updated);
    }

    private static Runnable waitingLoad(CountDownLatch inside, CountDownLatch release, String timeout) {
        return () -> {
            inside.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(timeout);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    private static void assertOriginalLinesRemain(String original, String merged) {
        List<String> before = contentLines(original);
        List<String> after = contentLines(merged);
        int position = 0;
        for (String line : before) {
            int found = -1;
            for (int i = position; i < after.size(); i++) {
                if (line.equals(after.get(i))) {
                    found = i;
                    break;
                }
            }
            assertTrue(found >= 0, "rewritten line: " + line);
            position = found + 1;
        }
    }

    private static void assertParsedCharactersMatch(String original, String merged) throws Exception {
        YamlConfiguration before = yaml(original);
        YamlConfiguration after = yaml(merged);
        ConfigurationSection entries = before.getConfigurationSection("entries");
        assertTrue(entries != null, merged);
        for (String playerId : entries.getKeys(false)) {
            ConfigurationSection characters = entries.getConfigurationSection(playerId);
            assertTrue(characters != null, playerId);
            for (String characterId : characters.getKeys(false)) {
                String base = "entries." + playerId + "." + characterId;
                assertEquals(before.getLong(base + ".updated"), after.getLong(base + ".updated"), base);
                assertEquals(before.getString(base + ".summary"), after.getString(base + ".summary"), base);
                assertEquals(before.getMapList(base + ".lines"), after.getMapList(base + ".lines"), base);
            }
        }
    }

    private static List<String> contentLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static Set<String> keysIn(String text) throws Exception {
        Set<String> keys = new java.util.HashSet<>();
        ConfigurationSection entries = yaml(text).getConfigurationSection("entries");
        assertTrue(entries != null, text);
        for (String playerId : entries.getKeys(false)) {
            ConfigurationSection characters = entries.getConfigurationSection(playerId);
            assertTrue(characters != null, playerId);
            for (String characterId : characters.getKeys(false)) {
                keys.add(playerId + "\u0000" + characterId);
            }
        }
        return keys;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (from <= text.length() - needle.length()) {
            int found = text.indexOf(needle, from);
            if (found < 0) {
                break;
            }
            count++;
            from = found + needle.length();
        }
        return count;
    }

    private static boolean hasContinuation(String text) {
        for (String line : text.split("\\R", -1)) {
            if (!line.isBlank() && line.indexOf(':') < 0) {
                return true;
            }
        }
        return false;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(text);
        return parsed;
    }

    private static String firstText(YamlConfiguration parsed, UUID player, String character) {
        List<Map<?, ?>> lines = parsed.getMapList("entries." + player + "." + character + ".lines");
        assertFalse(lines.isEmpty(), character);
        return String.valueOf(lines.get(0).get("text"));
    }

    private static void awaitFinished(DialogueMemoryPersistence files) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!files.loadFinished()) {
            if (System.nanoTime() > deadline) {
                fail("loader did not finish");
            }
            Thread.sleep(5);
        }
    }

    private static String line(MemoryStore store, UUID player, String character) {
        List<TurnMemory.Line> lines = store.transcript(player, character, 80L, 8, 8_000, 0L);
        assertFalse(lines.isEmpty(), character);
        return lines.get(0).text();
    }

    private static long millisSince(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static List<String> warnings(List<LogRecord> records, String text) {
        List<String> found = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel() != Level.WARNING || record.getMessage() == null) {
                continue;
            }
            if (record.getMessage().contains(text)) {
                found.add(record.getMessage());
            }
        }
        return found;
    }

    private static void assertSecretsHidden(List<LogRecord> records) {
        for (LogRecord record : records) {
            String message = record.getMessage() == null ? "" : record.getMessage();
            assertFalse(message.contains(SECRET), message);
            if (record.getThrown() != null) {
                assertFalse(String.valueOf(record.getThrown()).contains(SECRET), record.getThrown().toString());
            }
        }
    }

    private static void assumePosix(Path dir) {
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null,
                "POSIX permissions are not available");
    }

    private static final String FOLDED =
            ("alpha: \"double\" and 'single' # hash - dash Привет 😀 ").repeat(4);
    private static final String BLOCKED = "- leading: \"double\" and 'single' # hash\nПривет мир";

    private static Fixture fixture(String name) throws Exception {
        return fixture(name, false);
    }

    private static Fixture fixture(String name, boolean summaries) throws Exception {
        Path root = Files.createTempDirectory("nai-memory");
        Path dir = root.resolve("kp_" + SECRET).resolve("NexusAI");
        Files.createDirectories(dir);
        UUID player = UUID.randomUUID();
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
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger("memory-" + name + "-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        MemoryStore store = new MemoryStore();
        store.secrets(() -> List.of(SECRET));
        AtomicLong clock = new AtomicLong(1_000_000L);
        DialogueSettings settings = persisting(summaries);
        DialogueMemoryPersistence files = new DialogueMemoryPersistence(
                store,
                file::toFile,
                () -> settings,
                () -> List.of(SECRET),
                logger,
                clock::get);
        return new Fixture(file, player, store, files, records, clock);
    }

    private static DialogueSettings persisting(boolean summaries) {
        DialogueSettings defaults = DialogueSettings.defaults();
        return new DialogueSettings(
                defaults.dialogueEnabled(),
                defaults.memoryTurns(),
                true,
                defaults.memoryMaxChars(),
                defaults.memoryExpiryHours(),
                defaults.sessionTimeoutSeconds(),
                defaults.leaveRadius(),
                defaults.maxRepliesPerSession(),
                defaults.messageCooldownMillis(),
                defaults.conversationsPerPlayerPerDay(),
                defaults.maxMessageLength(),
                defaults.cacheGreeting(),
                defaults.greetingCacheSeconds(),
                defaults.actionsEnabled(),
                defaults.actionLog(),
                defaults.maxActionsPerReply(),
                summaries,
                defaults.summaryThresholdTurns(),
                defaults.summaryMaxChars(),
                defaults.summaryMaxTokens(),
                defaults.summaryProvider(),
                defaults.summaryModel());
    }

    private record Fixture(
            Path file,
            UUID player,
            MemoryStore store,
            DialogueMemoryPersistence files,
            List<LogRecord> records,
            AtomicLong clock
    ) {
    }
}
