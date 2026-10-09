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
        MemoryStore.beforeKeyScan = null;
        MemoryStore.beforeReplace = null;
        MemoryStore.failNextPublish = false;
        MemoryStore.abandonActiveLoad = false;
        DialogueMemoryPersistence.loaderThreads = DialogueMemoryPersistence.DEFAULT_LOADER_THREADS;
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 1_000L;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 5_000L;
        MemoryStore.fullDocumentAppends.set(0);
        MemoryStore.eventScans.set(0);
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
            System.out.println("large first-load stop " + elapsed + " ms, cpus="
                    + Runtime.getRuntime().availableProcessors()
                    + ", heapMax=" + Runtime.getRuntime().maxMemory());
            assertTrue(elapsed < 20_000L, "stop took " + elapsed + " ms");
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
            System.out.println("large saved stop " + elapsed + " ms, bytes=" + bytes
                    + ", cpus=" + Runtime.getRuntime().availableProcessors()
                    + ", heapMax=" + Runtime.getRuntime().maxMemory());
            assertTrue(elapsed < 20_000L, "stop took " + elapsed + " ms");
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
    void stopReplacesAHandWrittenFormatValue() throws Exception {
        stopReplacesFormat("format: 1");
        stopReplacesFormat("format: \"1\"");
        stopReplacesFormat("format: '1'");
    }

    @Test
    void stopDuringAQaSizedSavedLoadStaysNearTheJoinGrace() throws Exception {
        Fixture fixture = fixture("stop-qa-sized", true);
        int players = 2_501;
        int characters = 4;
        String lineText = "It's fine, say \"hi\". " + "m".repeat(16);
        String summary = "note";
        MemoryStore writer = new MemoryStore();
        for (int p = 0; p < players; p++) {
            UUID id = qaPlayer(p);
            for (int c = 0; c < characters; c++) {
                List<TurnMemory.Line> lines = new ArrayList<>(16);
                for (int n = 0; n < 16; n++) {
                    String text = n == 0 ? lineText + "-" + p + "-" + c : lineText;
                    lines.add(new TurnMemory.Line(n % 2 == 0 ? "user" : "assistant", text));
                }
                writer.get(id, "npc_" + c).load(lines, 40L, summary, 40L);
            }
        }
        writer.save(fixture.file.toFile(), null, true);
        writer = null;
        long bytes = Files.size(fixture.file);
        assertTrue(bytes >= 12_000_000L && bytes <= 14_500_000L, "saved file is " + bytes + " bytes");
        String token = lineText + "-1000-1";
        assertTrue(Files.readString(fixture.file).contains(token));
        UUID first = qaPlayer(0);
        UUID created = UUID.randomUUID();
        fixture.store.append(first, "npc_0", "user", "must-not-overwrite", 70L, 8, 8_000, 0L);
        fixture.store.append(first, "npc_new", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        fixture.store.append(created, "npc_d", "user", "new-player", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "qa-sized load was not released");
        try {
            MemoryStore.fullDocumentAppends.set(0);
            MemoryStore.eventScans.set(0);
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            int cpus = Runtime.getRuntime().availableProcessors();
            long heap = Runtime.getRuntime().maxMemory();
            System.out.println("qa-shaped stop " + elapsed + " ms, bytes=" + bytes
                    + ", cpus=" + cpus + ", heapMax=" + heap
                    + ", eventScans=" + MemoryStore.eventScans.get()
                    + ", fullDocumentAppends=" + MemoryStore.fullDocumentAppends.get());
            assertEquals(0, MemoryStore.fullDocumentAppends.get(), "stop rewrote the file");
            assertTrue(MemoryStore.eventScans.get() > 0, "stop did not scan keys");
            assertTrue(elapsed < 20_000L, "stop took " + elapsed + " ms, cpus=" + cpus + ", heapMax=" + heap);
            String merged = Files.readString(fixture.file);
            assertTrue(merged.startsWith("format: 2"), merged.substring(0, Math.min(40, merged.length())));
            assertEquals(1, countOf(merged, "only-in-memory"));
            assertEquals(1, countOf(merged, "new-player"));
            assertEquals(1, countOf(merged, token));
            assertFalse(merged.contains("must-not-overwrite"));
            Set<String> keys = MemoryStore.scanCharacterKeys(merged);
            assertTrue(keys != null, "merged file was not scanned");
            assertEquals(players * characters + 2, keys.size());
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(30_000L);
                assertFalse(pending.isAlive());
            }
            assertEquals(merged, Files.readString(fixture.file));
        } finally {
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(30_000L);
            }
        }
    }

    private void stopReplacesFormat(String formatLine) throws Exception {
        Fixture fixture = fixture("stop-format-" + formatLine.hashCode(), true);
        UUID player = fixture.player;
        String originalBody = """
                %s
                entries:
                  %s:
                    keeper:
                      updated: 40
                      lines:
                      - role: user
                        text: kept-on-disk
                """.formatted(formatLine, player);
        Files.writeString(fixture.file, originalBody, StandardCharsets.UTF_8);
        fixture.store.append(player, "keeper", "user", "must-not-overwrite", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "format load was not released");
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            MemoryStore.fullDocumentAppends.set(0);
            fixture.files.shutdown();
            String merged = Files.readString(fixture.file);
            int formats = 0;
            for (String line : merged.split("\\R")) {
                if (line.trim().startsWith("format:")) {
                    formats++;
                }
            }
            assertEquals(1, formats, formatLine + "\n" + merged);
            assertEquals(2, yaml(merged).getInt("format"), merged);
            assertTrue(merged.contains("kept-on-disk"), merged);
            assertEquals(1, countOf(merged, "only-in-memory"));
            assertFalse(merged.contains("must-not-overwrite"), merged);
            assertFalse(merged.contains("format: 1"), merged);
            assertFalse(merged.contains("format: \"1\""), merged);
            assertFalse(merged.contains("format: '1'"), merged);
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

    @Test
    void stopWarnsAndDoesNotWriteWhenTheKeyScanExceedsTheBudget() throws Exception {
        Fixture fixture = fixture("stop-budget");
        Files.writeString(fixture.file, "entries:\n  " + fixture.player + ":\n    keeper:\n      updated: 1\n",
                StandardCharsets.UTF_8);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        byte[] before = Files.readAllBytes(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch scanInside = new CountDownLatch(1);
        CountDownLatch scanRelease = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "budget load was not released");
        MemoryStore.beforeKeyScan = () -> {
            scanInside.countDown();
            try {
                if (!scanRelease.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("budget scan was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        long previousBudget = DialogueMemoryPersistence.shutdownAppendBudgetMillis;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 200L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.clock.addAndGet(DialogueMemoryPersistence.LOAD_HANG_WARNING_AFTER_MILLIS);
            fixture.files.shutdown();
            assertTrue(scanInside.await(5, TimeUnit.SECONDS));
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 1 dialogue characters because it did not finish within the stop budget.",
                    notes.get(0));
            assertEquals(0, warnings(fixture.records, PAUSED).size(), fixture.records.toString());
            assertEquals(1, fixture.records.stream().filter(record -> record.getLevel() == Level.WARNING).count(),
                    fixture.records.toString());
        } finally {
            DialogueMemoryPersistence.shutdownAppendBudgetMillis = previousBudget;
            MemoryStore.beforeKeyScan = null;
            MemoryStore.pauseDuringLoad = null;
            scanRelease.countDown();
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void stopWarnsAndDoesNotWriteWhenTheKeyScanCrashes() throws Exception {
        Fixture fixture = fixture("stop-scan-crash");
        Files.writeString(fixture.file, "entries:\n  " + fixture.player + ":\n    keeper:\n      updated: 1\n",
                StandardCharsets.UTF_8);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        byte[] before = Files.readAllBytes(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "crash load was not released");
        MemoryStore.beforeKeyScan = () -> {
            throw new IllegalStateException("key scan failed");
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 1 dialogue characters because the key scan failed.",
                    notes.get(0));
            assertEquals(0, warnings(fixture.records, PAUSED).size(), fixture.records.toString());
            assertEquals(1, fixture.records.stream().filter(record -> record.getLevel() == Level.WARNING).count(),
                    fixture.records.toString());
        } finally {
            MemoryStore.beforeKeyScan = null;
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    private static UUID qaPlayer(int index) {
        return new UUID(0x4e78000000000000L, index + 1L);
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
    void stopLeavesAFlowFileUntouchedWhenTheLoadDoesNotFinish() throws Exception {
        Fixture fixture = fixture("stop-flow-budget");
        UUID player = fixture.player;
        String flow = "entries:\n  " + player + ":\n    shared: {updated: 40, lines: [{role: user, text: kept-flow}]}\n";
        Files.writeString(fixture.file, flow, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(player, "shared", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "flow load was not released");
        long previousBudget = DialogueMemoryPersistence.shutdownAppendBudgetMillis;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 200L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            MemoryStore.fullDocumentAppends.set(0);
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            assertEquals(0, MemoryStore.fullDocumentAppends.get(), "stop parsed the file again");
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 2 dialogue characters because it did not finish within the stop budget.",
                    notes.get(0));
            assertNoExtraReader();
            assertNoTempFile(fixture.file.getParent());
            long limit = DialogueMemoryPersistence.shutdownLoadGraceMillis + 200L + 1_500L;
            assertTrue(elapsed <= limit, "stop took " + elapsed + " ms");
        } finally {
            DialogueMemoryPersistence.shutdownAppendBudgetMillis = previousBudget;
            MemoryStore.pauseDuringLoad = null;
            release.countDown();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
        }
    }

    @Test
    void stopSavesAFlowFileWhenTheLoadFinishes() throws Exception {
        Fixture fixture = fixture("stop-flow-save");
        UUID player = fixture.player;
        String flow = "entries:\n  " + player + ":\n    shared: {updated: 40, lines: [{role: user, text: kept-flow}]}\n";
        Files.writeString(fixture.file, flow, StandardCharsets.UTF_8);
        fixture.store.append(player, "shared", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        MemoryStore.fullDocumentAppends.set(0);
        fixture.files.onReload();
        fixture.files.shutdown();
        Thread pending = fixture.files.diskLoader();
        if (pending != null) {
            pending.join(5_000L);
        }
        String merged = Files.readString(fixture.file);
        YamlConfiguration loaded = yaml(merged);
        List<Map<?, ?>> shared = loaded.getMapList("entries." + player + ".shared.lines");
        assertEquals("kept-flow", String.valueOf(shared.get(0).get("text")), merged);
        assertTrue(merged.contains("only-in-memory"), merged);
        assertFalse(merged.contains("said-while-off"), merged);
        assertEquals(0, MemoryStore.fullDocumentAppends.get(), merged);
        assertEquals(0, warnings(fixture.records, "Did not save").size(), fixture.records.toString());
        assertNoExtraReader();
    }

    @Test
    void stopDuringARefusedLoadDoesNotReadTheFileAgain() throws Exception {
        Fixture fixture = fixture("stop-refused-live");
        int chars = (int) Math.min(256_000L, Math.max(48_000L, Runtime.getRuntime().maxMemory() / 2_048L));
        String pad = "m".repeat(120);
        StringBuilder body = new StringBuilder(chars + 256);
        body.append("entries:\n  ").append(fixture.player).append(":\n    npc:\n      updated: 1\n      lines:\n");
        body.append("      - role: user\n        text: ").append(pad).append('\n');
        while (body.length() < chars) {
            body.append("pad_").append(body.length()).append(": ").append(pad).append('\n');
        }
        body.append("zz_anchor: &a x\nzz_alias: *a\n");
        Files.writeString(fixture.file, body, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(fixture.player, "npc_new", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        MemoryStore.fullDocumentAppends.set(0);
        long started = System.nanoTime();
        fixture.files.onReload();
        fixture.files.shutdown();
        long elapsed = millisSince(started);
        long limit = DialogueMemoryPersistence.shutdownLoadGraceMillis
                + DialogueMemoryPersistence.shutdownAppendBudgetMillis
                + 1_500L;
        assertTrue(elapsed <= limit, "stop took " + elapsed + " ms");
        assertEquals(0, MemoryStore.fullDocumentAppends.get(), "stop parsed the file again");
        assertNoExtraReader();
        assertNoTempFile(fixture.file.getParent());
        assertFalse(fixture.records.stream().anyMatch(record ->
                String.valueOf(record.getMessage()).contains("OutOfMemoryError")
                        || (record.getThrown() != null && record.getThrown() instanceof OutOfMemoryError)),
                fixture.records.toString());
        byte[] after = Files.readAllBytes(fixture.file);
        if (java.util.Arrays.equals(before, after)) {
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertTrue(notes.get(0).contains("stop budget"), notes.get(0));
        } else {
            assertTrue(Files.readString(fixture.file).contains("only-in-memory"));
            assertEquals(0, warnings(fixture.records, "Did not save").size(), fixture.records.toString());
        }
        Thread pending = fixture.files.diskLoader();
        if (pending != null) {
            pending.join(5_000L);
        }
    }

    @Test
    void stopDoesNotRenameAfterTheBudget() throws Exception {
        Fixture fixture = fixture("stop-write-budget");
        UUID player = fixture.player;
        String flow = "entries:\n  " + player + ":\n    shared: {updated: 40, lines: [{role: user, text: kept-flow}]}\n";
        Files.writeString(fixture.file, flow, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = () -> {
            inside.countDown();
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        MemoryStore.beforeReplace = () -> {
            try {
                Thread.sleep(3_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        long previousGrace = DialogueMemoryPersistence.shutdownLoadGraceMillis;
        long previousBudget = DialogueMemoryPersistence.shutdownAppendBudgetMillis;
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 200L;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 2_000L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            MemoryStore.fullDocumentAppends.set(0);
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            assertEquals(0, MemoryStore.fullDocumentAppends.get());
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertTrue(notes.get(0).contains("stop budget"), notes.get(0));
            assertNoTempFile(fixture.file.getParent());
            assertEquals(0, warnings(fixture.records, "could not be read").size(), fixture.records.toString());
        } finally {
            DialogueMemoryPersistence.shutdownLoadGraceMillis = previousGrace;
            DialogueMemoryPersistence.shutdownAppendBudgetMillis = previousBudget;
            MemoryStore.pauseDuringLoad = null;
            MemoryStore.beforeReplace = null;
        }
    }

    @Test
    void stopNamesAWriteFailureAsCouldNotBeWritten() throws Exception {
        Fixture fixture = fixture("stop-unwritten");
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        MemoryStore.failNextPublish = true;
        try {
            fixture.files.onReload();
            Thread pending = fixture.files.diskLoader();
            if (pending != null) {
                pending.join(5_000L);
            }
            fixture.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 2 dialogue characters because dialogue-memory.yml could not be written.",
                    notes.get(0));
            assertEquals(0, warnings(fixture.records, "could not be read").size(), fixture.records.toString());
            assertNoTempFile(fixture.file.getParent());
        } finally {
            MemoryStore.failNextPublish = false;
        }
    }

    @Test
    void stopReportsScanOutOfMemoryWithoutWaitingTheBudget() throws Exception {
        Fixture fixture = fixture("stop-scan-oom");
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release, "oom load was not released");
        MemoryStore.beforeKeyScan = () -> {
            throw new OutOfMemoryError("sim");
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            fixture.files.shutdown();
            long elapsed = millisSince(started);
            assertArrayEquals(before, Files.readAllBytes(fixture.file));
            List<String> notes = warnings(fixture.records, "Did not save");
            assertEquals(1, notes.size(), fixture.records.toString());
            assertEquals(
                    "Did not save 1 dialogue characters because the key scan failed.",
                    notes.get(0));
            assertEquals(0, warnings(fixture.records, "stop budget").size(), fixture.records.toString());
            long limit = DialogueMemoryPersistence.shutdownLoadGraceMillis + 1_500L;
            assertTrue(elapsed <= limit, "stop waited the budget: " + elapsed + " ms");
        } finally {
            MemoryStore.beforeKeyScan = null;
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

    private static void assertNoExtraReader() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            if ("nexusai-memory-merge".equals(name) || "nexusai-memory-keys".equals(name)) {
                fail("still reading: " + name);
            }
        }
    }

    private static void assertNoTempFile(Path dir) throws Exception {
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "a temporary file was left behind");
        }
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
