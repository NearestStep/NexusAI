package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NAI-114: one INFO line when stop runs during the first dialogue memory load. */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class StopBudgetLogTest {
    /**
     * Latches and joins wait longer than the 5 minute test timeout, so a test host pause cannot make a
     * paused load give up and leave memory unloaded; only the test timeout ends a stuck test.
     */
    private static final long LATCH_WAIT_MINUTES = 10L;


    private static final String PREFIX = "Stop ran during the first dialogue memory load";

    @AfterEach
    void reset() {
        MemoryStore.pauseDuringLoad = null;
        MemoryStore.beforeKeyScan = null;
        MemoryStore.abandonActiveLoad = false;
        MemoryStore.refuseSpliceEdits = null;
        MemoryStore.afterLoadPublish = null;
        MemoryStore.afterStopCount = null;
        MemoryStore.beforeReplace = null;
        MemoryStore.beforeCharacterBlock = null;
        MemoryStore.beforeDocumentDump = null;
        DialogueMemoryPersistence.loaderThreads = DialogueMemoryPersistence.DEFAULT_LOADER_THREADS;
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 1_000L;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 5_000L;
    }

    @Test
    void lineText() {
        assertEquals("Stop ran during the first dialogue memory load and used the stop budget: "
                        + "5012 ms for a 96 KiB dialogue-memory.yml, 640 ms used. "
                        + "4 dialogue characters held in memory: appended 3, already in file 1.",
                DialogueMemoryPersistence.budgetStopLine(5_012L, 96L * 1024L, 640L, 3, 1, 4));
        assertEquals("Stop ran during the first dialogue memory load and used the stop budget: "
                        + "5000 ms for a 1 KiB dialogue-memory.yml, 0 ms used. "
                        + "1 dialogue character held in memory: appended 0, already in file 0.",
                DialogueMemoryPersistence.budgetStopLine(5_000L, 1L, 0L, 0, 0, 1));
        String empty = DialogueMemoryPersistence.budgetStopLine(5_000L, 0L, 0L, 0, 0, 0);
        assertTrue(empty.contains(" 0 KiB "), empty);
        assertTrue(empty.endsWith(" 0 dialogue characters held in memory: appended 0, already in file 0."), empty);
    }

    @Test
    void stopDuringTheFirstLoadLogsTheBudgetAndWhatWasWritten() throws Exception {
        Fixture fixture = fixture("written", true);
        fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        long bytes = Files.size(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertTrue(Files.readString(fixture.file).contains("only-in-memory"));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            String line = lines.getFirst();
            long budget = DialogueMemoryPersistence.appendBudgetMillis(bytes);
            assertTrue(line.startsWith(PREFIX + " and used the stop budget: " + budget + " ms for a "
                    + ((bytes + 1023) / 1024) + " KiB dialogue-memory.yml, "), line);
            assertTrue(line.matches(".*, \\d+ ms used\\. .*"), line);
            assertTrue(line.endsWith("2 dialogue characters held in memory: appended 1, already in file 1."), line);
        } finally {
            release.countDown();
            join(fixture);
        }
    }

    @Test
    void characterAlreadyInTheFileIsReportedAsAlreadyInFileNotAsLost() throws Exception {
        Fixture fixture = fixture("already", true);
        fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        byte[] before = Files.readAllBytes(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(fixture.file));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().endsWith("1 dialogue character held in memory: appended 0, already in file 1."),
                    lines.getFirst());
            assertEquals(0, fixture.records.stream()
                    .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().startsWith("Did not save "))
                    .count(), fixture.records.toString());
        } finally {
            release.countDown();
            join(fixture);
        }
    }

    /**
     * The splice counts one character as already in the file, then cannot edit the file in place.
     * Stop waits for the load and writes the whole document, so both characters are appended and
     * the already-in-file count from the splice must not be added on top.
     */
    @Test
    void fullDocumentSaveAfterARefusedSpliceKeepsTheCountsWithinTheHeldCount() throws Exception {
        Fixture fixture = fixture("refused-splice", true);
        fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
        MemoryStore.refuseSpliceEdits = () -> {
            refused.incrementAndGet();
            release.countDown();
            return true;
        };
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertEquals(1, refused.get(), fixture.records.toString());
            String written = Files.readString(fixture.file);
            assertTrue(written.contains("only-in-memory"), written);
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().endsWith("2 dialogue characters held in memory: appended 2, already in file 0."),
                    lines.getFirst());
        } finally {
            release.countDown();
            join(fixture);
        }
    }

    /**
     * The first load puts the file's characters into memory only after stop has taken its count.
     * Those characters were not held when stop began, so they are neither appended nor already in
     * the file for this line, and a character added to memory after that point is not counted either.
     */
    @Test
    void charactersLoadedAfterStopBeganAreNotCounted() throws Exception {
        assertEquals("1 dialogue character held in memory: appended 1, already in file 0.",
                stopWhileTheLoadPublishesAfterTheCount("loaded-after-count", true));
    }

    @Test
    void emptyMemoryAtStopIgnoresCharactersTheLoadAddsLater() throws Exception {
        assertEquals("0 dialogue characters held in memory: appended 0, already in file 0.",
                stopWhileTheLoadPublishesAfterTheCount("empty-at-count", false));
    }

    /**
     * Two characters are held when stop begins: blacksmith is already in the file, innkeeper is
     * not. After the count, the load puts blacksmith and miller from the file into memory, and a
     * new character, harbor, arrives. Only innkeeper is appended and only blacksmith is already in
     * the file. Counting harbor as appended (no snapshot filter where appended characters are
     * counted) gives "appended 2, already in file 0".
     */
    @Test
    void lateCharactersInAndOutOfTheFileDoNotChangeAppendedOrAlreadyInFile() throws Exception {
        assertEquals("2 dialogue characters held in memory: appended 1, already in file 1.",
                stopWithLateCharacters("late-in-and-out", false));
    }

    /**
     * The same stop, but the splice crosses the budget before it can replace the file, so nothing
     * is appended. Only blacksmith is already in the file. Counting miller, which the load put into
     * memory after the count, (no snapshot filter where already-in-file characters are counted)
     * gives "already in file 2"; the cap at held minus appended does not hide that.
     */
    @Test
    void lateFileCharacterIsNotAlreadyInFileWhenNothingIsAppended() throws Exception {
        assertEquals("2 dialogue characters held in memory: appended 0, already in file 1.",
                stopWithLateCharacters("late-past-budget", true));
    }

    private static String stopWithLateCharacters(String name, boolean crossBudget) throws Exception {
        Fixture fixture = fixture(name, true);
        Files.writeString(fixture.file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: kept-from-disk
                    miller:
                      updated: 50
                      lines:
                      - role: user
                        text: miller-from-disk
                """.formatted(fixture.player), StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(fixture.file);
        fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        MemoryStore.afterLoadPublish = () -> {
            published.countDown();
            try {
                finish.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        MemoryStore.afterStopCount = () -> {
            release.countDown();
            try {
                assertTrue(published.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fixture.store.append(fixture.player, "harbor", "user", "after-stop-began", 70L, 8, 8_000, 0L);
        };
        if (crossBudget) {
            DialogueMemoryPersistence.shutdownAppendBudgetMillis = 300L;
            MemoryStore.beforeReplace = () -> {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
        }
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            String written = Files.readString(fixture.file);
            if (crossBudget) {
                assertEquals(new String(before, StandardCharsets.UTF_8), written);
            } else {
                assertTrue(written.contains("only-in-memory"), written);
                assertTrue(written.contains("miller-from-disk"), written);
            }
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            String line = lines.getFirst();
            int at = line.indexOf(" used. ");
            assertTrue(at > 0, line);
            return line.substring(at + " used. ".length());
        } finally {
            finish.countDown();
            release.countDown();
            join(fixture);
        }
    }

    /**
     * The getters cap what the log line shows: appended at the held count, and already in file at
     * the held count minus appended. Raw counts above the held count are set directly.
     */
    @Test
    void stopCountsAreCappedAtTheHeldCount() throws Exception {
        MemoryStore store = new MemoryStore();
        UUID player = UUID.randomUUID();
        store.append(player, "blacksmith", "user", "one", 70L, 8, 8_000, 0L);
        store.append(player, "innkeeper", "user", "two", 70L, 8, 8_000, 0L);
        java.lang.reflect.Field writtenField = MemoryStore.class.getDeclaredField("stopWritten");
        java.lang.reflect.Field alreadyField = MemoryStore.class.getDeclaredField("stopAlreadyInFile");
        writtenField.setAccessible(true);
        alreadyField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger written =
                (java.util.concurrent.atomic.AtomicInteger) writtenField.get(store);
        java.util.concurrent.atomic.AtomicInteger already =
                (java.util.concurrent.atomic.AtomicInteger) alreadyField.get(store);
        store.beginStopCount(false);
        try {
            assertEquals(2, store.stopHeldCount(false));
            written.set(5);
            already.set(7);
            assertEquals(2, store.stopWrittenCount());
            assertEquals(0, store.stopAlreadyInFileCount());
            written.set(1);
            assertEquals(1, store.stopWrittenCount());
            assertEquals(1, store.stopAlreadyInFileCount());
            written.set(0);
            already.set(3);
            assertEquals(0, store.stopWrittenCount());
            assertEquals(2, store.stopAlreadyInFileCount());
        } finally {
            store.endStopCount();
        }
        assertEquals(0, store.stopWrittenCount());
        assertEquals(0, store.stopAlreadyInFileCount());
    }

    /**
     * The first load puts the file's characters into memory only after stop has taken its count,
     * and a new character arrives after that point too. None of them were held when stop began, so
     * they are neither appended nor already in the file for this line. Returns the line's tail.
     */
    private static String stopWhileTheLoadPublishesAfterTheCount(String name, boolean heldBeforeStop)
            throws Exception {
        Fixture fixture = fixture(name, true);
        if (heldBeforeStop) {
            fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        }
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        MemoryStore.afterLoadPublish = () -> {
            published.countDown();
            try {
                finish.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        MemoryStore.afterStopCount = () -> {
            release.countDown();
            try {
                assertTrue(published.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            fixture.store.append(fixture.player, "harbor", "user", "after-stop-began", 70L, 8, 8_000, 0L);
        };
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            if (heldBeforeStop) {
                assertTrue(Files.readString(fixture.file).contains("only-in-memory"));
            }
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            String line = lines.getFirst();
            int at = line.indexOf(" used. ");
            assertTrue(at > 0, line);
            return line.substring(at + " used. ".length());
        } finally {
            finish.countDown();
            release.countDown();
            join(fixture);
        }
    }

    @Test
    void stopPastTheBudgetLogsZeroWritten() throws Exception {
        Fixture fixture = fixture("budget", true);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        byte[] before = Files.readAllBytes(fixture.file);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch scanRelease = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        MemoryStore.beforeKeyScan = () -> {
            try {
                scanRelease.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 200L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(fixture.file));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().contains("used the stop budget: 200 ms for a "), lines.getFirst());
            assertTrue(lines.getFirst().endsWith("1 dialogue character held in memory: appended 0, already in file 0."),
                    lines.getFirst());
            assertEquals(1, fixture.records.stream()
                    .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().startsWith("Did not save 1 "))
                    .count(), fixture.records.toString());
        } finally {
            scanRelease.countDown();
            release.countDown();
            join(fixture);
        }
    }

    @Test
    void normalStopDoesNotLogTheLine() throws Exception {
        Fixture fixture = fixture("normal", true);
        fixture.files.loadedAtStartup();
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        fixture.files.shutdown();
        assertTrue(infos(fixture.records).isEmpty(), fixture.records.toString());

        Fixture finished = fixture("finished-load", true);
        finished.files.onReload();
        Thread loader = finished.files.diskLoader();
        if (loader != null) {
            loader.join(TimeUnit.MINUTES.toMillis(LATCH_WAIT_MINUTES));
        }
        assertTrue(finished.files.memoryLoadedFromDisk());
        finished.store.append(finished.player, "innkeeper", "user", "after-load", 70L, 8, 8_000, 0L);
        finished.files.shutdown();
        assertTrue(Files.readString(finished.file).contains("after-load"));
        assertTrue(infos(finished.records).isEmpty(), finished.records.toString());
    }

    @Test
    void logStopBudgetFalseKeepsTheLineOut() throws Exception {
        Fixture fixture = fixture("quiet", false);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertTrue(Files.readString(fixture.file).contains("only-in-memory"));
            assertTrue(infos(fixture.records).isEmpty(), fixture.records.toString());
        } finally {
            release.countDown();
            join(fixture);
        }
    }

    @Test
    void settingDefaultsToTrueAndReadsFalse() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        assertTrue(DialogueSettings.read(yaml).logStopBudget());
        assertTrue(DialogueSettings.defaults().logStopBudget());
        yaml.loadFromString("dialogue:\n  log-stop-budget: false\n");
        assertFalse(DialogueSettings.read(yaml).logStopBudget());
    }

    /**
     * The stop splice cannot build innkeeper's block (the hook throws, as YAML would for a value it
     * cannot write). The splice is refused instead of failing stop, stop waits for the load, and
     * the full save writes both characters with no warning.
     */
    @Test
    void spliceBlockThatCannotBeWrittenFallsBackToTheFullSave() throws Exception {
        Fixture fixture = fixture("bad-block", true);
        fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
        fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
        AtomicInteger rejected = new AtomicInteger();
        MemoryStore.beforeCharacterBlock = id -> {
            if ("innkeeper".equals(id)) {
                rejected.incrementAndGet();
                release.countDown();
                throw new IllegalStateException("block rejected for the test");
            }
        };
        int fullBefore = MemoryStore.fullDocumentAppends.get();
        try {
            fixture.files.onReload();
            assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
            fixture.files.shutdown();
            assertTrue(rejected.get() > 0, fixture.records.toString());
            String written = Files.readString(fixture.file);
            assertTrue(written.contains("only-in-memory"), written);
            assertTrue(written.contains("kept-from-disk"), written);
            assertEquals(fullBefore, MemoryStore.fullDocumentAppends.get(), "stop must not read the file a second time");
            assertEquals(List.of(), warnings(fixture.records));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().endsWith("2 dialogue characters held in memory: appended 2, already in file 0."),
                    lines.getFirst());
        } finally {
            release.countDown();
            join(fixture);
        }
    }

    /**
     * Stop writes the whole document, and YAML cannot write one held character (the hook rejects
     * any document that holds it), so that character is left out. It is not counted as appended,
     * and not as already in the file, since the new file does not have it.
     */
    @Test
    void characterLeftOutOfTheFullSaveAtStopIsNotCountedAsAppended() throws Exception {
        for (String broken : List.of("innkeeper", "blacksmith")) {
            Fixture fixture = fixture("left-out-" + broken, true);
            fixture.store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
            fixture.store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
            long bytes = Files.size(fixture.file);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MemoryStore.pauseDuringLoad = waitingLoad(inside, release);
            MemoryStore.refuseSpliceEdits = () -> {
                release.countDown();
                return true;
            };
            MemoryStore.beforeDocumentDump = document -> {
                if (LoneSurrogateTest.holdsKey(document.get("entries"), broken)) {
                    throw new IllegalStateException("rejected for the test");
                }
            };
            try {
                fixture.files.onReload();
                assertTrue(inside.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES));
                fixture.files.shutdown();
                String written = Files.readString(fixture.file);
                assertFalse(written.contains(broken + ":"), written);
                assertTrue(written.contains("innkeeper".equals(broken) ? "kept-from-disk" : "only-in-memory"), written);
                assertEquals(List.of("Left 1 dialogue character out of dialogue-memory.yml because YAML could not "
                        + "write it; the other characters were saved (IllegalStateException: rejected for the test)"),
                        warnings(fixture.records));
                List<String> lines = infos(fixture.records);
                assertEquals(1, lines.size(), fixture.records.toString());
                String line = lines.getFirst();
                String used = line.replaceFirst("^.* dialogue-memory\\.yml, (\\d+) ms used\\. .*$", "$1");
                assertEquals(DialogueMemoryPersistence.budgetStopLine(DialogueMemoryPersistence.appendBudgetMillis(bytes),
                        bytes, Long.parseLong(used), 1, 0, 2), line);
                assertEquals("Stop ran during the first dialogue memory load and used the stop budget: "
                        + DialogueMemoryPersistence.appendBudgetMillis(bytes) + " ms for a 1 KiB dialogue-memory.yml, "
                        + used + " ms used. 2 dialogue characters held in memory: appended 1, already in file 0.", line);
            } finally {
                release.countDown();
                join(fixture);
                MemoryStore.beforeDocumentDump = null;
                MemoryStore.refuseSpliceEdits = null;
                MemoryStore.pauseDuringLoad = null;
            }
        }
    }

    /**
     * The full-document merge (no load running) leaves out a character YAML cannot write. The stop
     * counts drop it: a character only in memory is not appended, and one that was in the file is
     * no longer already in the file.
     */
    @Test
    void characterLeftOutOfTheFullDocumentMergeIsNotCounted() throws Exception {
        for (String broken : List.of("innkeeper", "blacksmith")) {
            Fixture fixture = fixture("merge-" + broken, true);
            MemoryStore store = fixture.store;
            store.append(fixture.player, "blacksmith", "user", "said-while-off", 70L, 8, 8_000, 0L);
            store.append(fixture.player, "innkeeper", "user", "only-in-memory", 70L, 8, 8_000, 0L);
            MemoryStore.refuseSpliceEdits = () -> true;
            MemoryStore.beforeDocumentDump = document -> {
                if (LoneSurrogateTest.holdsKey(document.get("entries"), broken)) {
                    throw new IllegalStateException("rejected for the test");
                }
            };
            store.beginStopCount(false);
            try {
                assertTrue(store.appendCharactersAbsentFromFile(fixture.file.toFile(), Logger.getAnonymousLogger(), false, List.of()));
                MemoryStore.beforeDocumentDump = null;
                String written = Files.readString(fixture.file);
                assertFalse(written.contains(broken + ":"), written);
                assertEquals(2, store.stopHeldCount(false));
                if ("innkeeper".equals(broken)) {
                    assertTrue(written.contains("kept-from-disk"), written);
                    assertEquals(0, store.stopWrittenCount(), "appended");
                    assertEquals(1, store.stopAlreadyInFileCount(), "already in file");
                } else {
                    assertTrue(written.contains("only-in-memory"), written);
                    assertEquals(1, store.stopWrittenCount(), "appended");
                    assertEquals(0, store.stopAlreadyInFileCount(), "already in file");
                }
            } finally {
                store.endStopCount();
                MemoryStore.beforeDocumentDump = null;
                MemoryStore.refuseSpliceEdits = null;
            }
        }
    }

    private static List<String> warnings(List<LogRecord> records) {
        synchronized (records) {
            return records.stream()
                    .filter(r -> r.getLevel() == Level.WARNING)
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private static List<String> infos(List<LogRecord> records) {
        synchronized (records) {
            return records.stream()
                    .filter(r -> r.getLevel() == Level.INFO && r.getMessage().startsWith(PREFIX))
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private static Runnable waitingLoad(CountDownLatch inside, CountDownLatch release) {
        return () -> {
            inside.countDown();
            try {
                if (!release.await(LATCH_WAIT_MINUTES, TimeUnit.MINUTES)) {
                    throw new IllegalStateException("load was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    private static void join(Fixture fixture) throws InterruptedException {
        Thread pending = fixture.files.diskLoader();
        if (pending != null) {
            pending.join(TimeUnit.MINUTES.toMillis(LATCH_WAIT_MINUTES));
        }
    }

    private static Fixture fixture(String name, boolean logStopBudget) throws Exception {
        Path dir = Files.createTempDirectory("nai-stop-budget-" + name);
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
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Logger logger = Logger.getLogger("stop-budget-" + name + "-" + UUID.randomUUID());
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
        AtomicLong clock = new AtomicLong(1_000_000L);
        DialogueSettings d = DialogueSettings.defaults();
        DialogueSettings settings = new DialogueSettings(
                d.dialogueEnabled(), d.memoryTurns(), true, d.memoryMaxChars(), d.memoryExpiryHours(),
                d.sessionTimeoutSeconds(), d.leaveRadius(), d.maxRepliesPerSession(), d.messageCooldownMillis(),
                d.conversationsPerPlayerPerDay(), d.maxMessageLength(), d.cacheGreeting(), d.greetingCacheSeconds(),
                d.actionsEnabled(), d.actionLog(), d.maxActionsPerReply(), false, d.summaryThresholdTurns(),
                d.summaryMaxChars(), d.summaryMaxTokens(), d.summaryProvider(), d.summaryModel(), logStopBudget);
        DialogueMemoryPersistence files = new DialogueMemoryPersistence(
                store, file::toFile, () -> settings, List::of, logger, clock::get);
        return new Fixture(file, player, store, files, records);
    }

    private record Fixture(
            Path file,
            UUID player,
            MemoryStore store,
            DialogueMemoryPersistence files,
            List<LogRecord> records
    ) {
    }
}
