package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Saves of {@code dialogue-memory.yml} write only when memory differs from the file, and do not
 * write back transcripts that expired while the server ran.
 */
class MemorySaveChangesTest {

    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_800_000_000_000L;
    private static final String LONG_TEXT =
            "the blacksmith says the old sword needs a new grip and some oil before the long trip north to the pass";

    @AfterEach
    void reset() {
        MemoryStore.pauseDuringLoad = null;
        MemoryStore.abandonActiveLoad = false;
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 1_000L;
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 5_000L;
        DialogueMemoryPersistence.loaderThreads = DialogueMemoryPersistence.DEFAULT_LOADER_THREADS;
    }

    // ---- F1: no change, no write ----

    @Test
    void stopAfterALoadWithoutChangesLeavesTheFileByteForByte() throws Exception {
        for (boolean folded : new boolean[] {false, true}) {
            Fixture fx = fixture(false);
            writeFile(fx.file, 300, T0, folded);
            byte[] before = Files.readAllBytes(fx.file);
            fx.files.onReload();
            awaitFinished(fx.files);
            assertTrue(fx.files.memoryLoadedFromDisk());
            long started = System.nanoTime();
            fx.files.shutdown();
            long elapsed = millisSince(started);
            assertArrayEquals(before, Files.readAllBytes(fx.file), "folded=" + folded);
            assertNoTempFile(fx.file.getParent());
            assertTrue(elapsed < 1_000L, "stop took " + elapsed + " ms");
            assertNoWarnings(fx.records);
        }
    }

    @Test
    void autosaveWithoutChangesDoesNotWrite() throws Exception {
        Fixture fx = fixture(false);
        writeFile(fx.file, 50, T0, false);
        FileTime old = FileTime.fromMillis(1_000_000L);
        Files.setLastModifiedTime(fx.file, old);
        byte[] before = Files.readAllBytes(fx.file);
        fx.files.onReload();
        awaitFinished(fx.files);
        fx.files.save();
        fx.files.save();
        assertArrayEquals(before, Files.readAllBytes(fx.file));
        assertEquals(old, Files.getLastModifiedTime(fx.file));
        assertNoTempFile(fx.file.getParent());
    }

    @Test
    void autosaveAfterAnAppendWritesAndTheNextOneDoesNot() throws Exception {
        Fixture fx = fixture(false);
        writeFile(fx.file, 20, T0, false);
        fx.files.onReload();
        awaitFinished(fx.files);
        fx.store.append(new UUID(7, 7), "npc_new", "user", "fresh-line", T0 + 5_000L, 8, 8_000, 168 * HOUR);
        fx.files.save();
        String saved = Files.readString(fx.file);
        assertTrue(saved.contains("fresh-line"), saved);
        FileTime old = FileTime.fromMillis(2_000_000L);
        Files.setLastModifiedTime(fx.file, old);
        fx.files.save();
        assertEquals(old, Files.getLastModifiedTime(fx.file), "a second save without changes wrote again");
        fx.store.append(new UUID(7, 7), "npc_new", "assistant", "second-line", T0 + 6_000L, 8, 8_000, 168 * HOUR);
        fx.files.shutdown();
        assertTrue(Files.readString(fx.file).contains("second-line"));
    }

    @Test
    void stopDuringAPausedLoadAndAfterAFinishedLoadBothKeepTheFile() throws Exception {
        for (boolean folded : new boolean[] {false, true}) {
            // Load still running when stop checks: the key scan finds nothing to add.
            Fixture during = fixture(false);
            writeFile(during.file, 400, T0, folded);
            byte[] before = Files.readAllBytes(during.file);
            DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MemoryStore.pauseDuringLoad = waiting(inside, release);
            during.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            during.files.shutdown();
            assertArrayEquals(before, Files.readAllBytes(during.file), "paused, folded=" + folded);
            release.countDown();
            joinLoader(during.files);
            MemoryStore.pauseDuringLoad = null;
            MemoryStore.abandonActiveLoad = false;
            assertArrayEquals(before, Files.readAllBytes(during.file), "after release, folded=" + folded);
            assertNoTempFile(during.file.getParent());

            // Load finished inside the join grace: memory equals the file, nothing to write.
            DialogueMemoryPersistence.shutdownLoadGraceMillis = 1_000L;
            Fixture after = fixture(false);
            writeFile(after.file, 400, T0, folded);
            byte[] again = Files.readAllBytes(after.file);
            CountDownLatch inside2 = new CountDownLatch(1);
            CountDownLatch release2 = new CountDownLatch(1);
            MemoryStore.pauseDuringLoad = waiting(inside2, release2);
            after.files.onReload();
            assertTrue(inside2.await(5, TimeUnit.SECONDS));
            Thread releaser = new Thread(() -> {
                sleep(100);
                release2.countDown();
            });
            releaser.start();
            after.files.shutdown();
            releaser.join();
            MemoryStore.pauseDuringLoad = null;
            assertTrue(after.files.memoryLoadedFromDisk());
            assertArrayEquals(again, Files.readAllBytes(after.file), "finished, folded=" + folded);
            assertNoTempFile(after.file.getParent());
        }
    }

    @Test
    void waitForLoadWithoutChangesSkipsTheWrite() throws Exception {
        Fixture fx = fixture(false);
        UUID player = new UUID(3, 3);
        // An anchor makes the key scan refuse, so stop waits for the load instead of splicing.
        String body = "entries:\n  " + player + ":\n    npc:\n      updated: " + T0 + "\n      lines:\n"
                + "      - role: user\n        text: kept\nzz_anchor: &a x\nzz_alias: *a\n";
        Files.writeString(fx.file, body, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(fx.file);
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waiting(inside, release);
        fx.files.onReload();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        Thread releaser = new Thread(() -> {
            sleep(300);
            release.countDown();
        });
        releaser.start();
        long started = System.nanoTime();
        fx.files.shutdown();
        long elapsed = millisSince(started);
        releaser.join();
        joinLoader(fx.files);
        assertTrue(fx.files.memoryLoadedFromDisk(), "load should finish inside the stop budget");
        assertArrayEquals(before, Files.readAllBytes(fx.file), "stop rewrote a file it had not changed");
        assertNoTempFile(fx.file.getParent());
        assertTrue(elapsed < 3_000L, "stop took " + elapsed + " ms");
        assertNoWarnings(fx.records);
    }

    @Test
    void waitForLoadWithAChangeStillWrites() throws Exception {
        Fixture fx = fixture(false);
        UUID player = new UUID(3, 4);
        String body = "entries:\n  " + player + ":\n    npc:\n      updated: " + T0 + "\n      lines:\n"
                + "      - role: user\n        text: kept\nzz_anchor: &a x\nzz_alias: *a\n";
        Files.writeString(fx.file, body, StandardCharsets.UTF_8);
        fx.store.append(new UUID(9, 9), "npc_mem", "user", "only-in-memory", T0, 8, 8_000, 168 * HOUR);
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waiting(inside, release);
        fx.files.onReload();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        Thread releaser = new Thread(() -> {
            sleep(300);
            release.countDown();
        });
        releaser.start();
        fx.files.shutdown();
        releaser.join();
        joinLoader(fx.files);
        String saved = Files.readString(fx.file);
        assertTrue(saved.contains("only-in-memory"), saved);
        assertTrue(saved.contains("kept"), saved);
    }

    // ---- long values ----

    @Test
    void longValuesStayOnOneLineAndARewriteKeepsTheSize() throws Exception {
        MemoryStore store = new MemoryStore();
        UUID player = new UUID(5, 5);
        for (int i = 0; i < 20; i++) {
            store.append(player, "npc_" + i, "user", LONG_TEXT + " " + i, T0, 8, 8_000, 0L);
        }
        Path file = Files.createTempDirectory("long-values").resolve("dialogue-memory.yml");
        store.save(file.toFile(), null, false);
        String first = Files.readString(file);
        for (String line : first.split("\n")) {
            assertTrue(line.isBlank() || line.indexOf(':') >= 0, "folded line: " + line);
        }
        MemoryStore reread = new MemoryStore();
        reread.load(file.toFile(), T0, 168 * HOUR, null);
        reread.save(file.toFile(), null, false);
        assertEquals(first, Files.readString(file));
    }

    @Test
    void anOlderFoldedFileReadsTheSameAndARewriteUnfoldsIt() throws Exception {
        Path file = Files.createTempDirectory("folded").resolve("dialogue-memory.yml");
        writeFile(file, 10, T0, true);
        String folded = Files.readString(file);
        assertTrue(folded.lines().anyMatch(line -> !line.isBlank() && line.indexOf(':') < 0), "fixture is not folded");
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), T0, 168 * HOUR, null);
        assertEquals(LONG_TEXT + " 0", store.transcript(new UUID(0, 1), "npc0", T0, 8, 8_000, 0L).get(0).text());
        store.save(file.toFile(), null, false);
        String rewritten = Files.readString(file);
        assertTrue(rewritten.length() < folded.length(), "rewrite did not unfold");
        MemoryStore again = new MemoryStore();
        again.load(file.toFile(), T0, 168 * HOUR, null);
        assertEquals(LONG_TEXT + " 0", again.transcript(new UUID(0, 1), "npc0", T0, 8, 8_000, 0L).get(0).text());
    }

    // ---- F2: expired while running ----

    @Test
    void stopDropsTranscriptsThatExpiredWhileRunning() throws Exception {
        for (boolean summaries : new boolean[] {false, true}) {
            Fixture fx = fixture(summaries);
            writeFile(fx.file, 30, T0, false);
            fx.files.onReload();
            awaitFinished(fx.files);
            fx.clock.set(T0 + 200 * HOUR);
            fx.files.shutdown();
            YamlConfiguration yaml = parse(fx.file);
            assertEquals(summaries ? 2 : 1, yaml.getInt("format"), Files.readString(fx.file));
            assertEquals(0, entryCount(yaml));
            assertNoTempFile(fx.file.getParent());
            MemoryStore reread = new MemoryStore();
            List<LogRecord> records = new ArrayList<>();
            assertTrue(reread.loadForPersistence(fx.file.toFile(), fx.clock.get(), 168 * HOUR, logger(records), List.of()));
            assertNoWarnings(records);
        }
    }

    @Test
    void autosaveDropsTranscriptsThatExpiredWhileRunning() throws Exception {
        Fixture fx = fixture(false);
        writeFile(fx.file, 30, T0, false);
        fx.files.onReload();
        awaitFinished(fx.files);
        fx.clock.set(T0 + 169 * HOUR);
        fx.files.save();
        YamlConfiguration yaml = parse(fx.file);
        assertEquals(0, entryCount(yaml));
        assertTrue(yaml.contains("format"));
    }

    @Test
    void mixedExpiredAndFreshKeepsOnlyTheFresh() throws Exception {
        Fixture fx = fixture(false);
        writeFile(fx.file, 10, T0, false);
        fx.files.onReload();
        awaitFinished(fx.files);
        UUID player = new UUID(8, 8);
        fx.store.append(player, "npc_fresh", "user", "still-fresh", T0 + 150 * HOUR, 8, 8_000, 168 * HOUR);
        fx.clock.set(T0 + 170 * HOUR);
        fx.files.shutdown();
        YamlConfiguration yaml = parse(fx.file);
        assertEquals(1, entryCount(yaml), Files.readString(fx.file));
        assertTrue(Files.readString(fx.file).contains("still-fresh"));
    }

    @Test
    void stopDuringALoadDoesNotAppendAnExpiredInMemoryTranscript() throws Exception {
        Fixture fx = fixture(false);
        writeFile(fx.file, 40, T0 + 100 * HOUR, false);
        byte[] before = Files.readAllBytes(fx.file);
        fx.store.append(new UUID(6, 6), "npc_old", "user", "expired-in-memory", T0, 8, 8_000, 168 * HOUR);
        fx.store.append(new UUID(6, 7), "npc_new", "user", "fresh-in-memory", T0 + 199 * HOUR, 8, 8_000, 168 * HOUR);
        fx.clock.set(T0 + 200 * HOUR);
        DialogueMemoryPersistence.shutdownLoadGraceMillis = 50L;
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MemoryStore.pauseDuringLoad = waiting(inside, release);
        fx.files.onReload();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        fx.files.shutdown();
        release.countDown();
        joinLoader(fx.files);
        String saved = Files.readString(fx.file);
        assertFalse(saved.contains("expired-in-memory"), "expired transcript was appended");
        assertTrue(saved.contains("fresh-in-memory"), "fresh transcript was not appended");
        assertTrue(saved.length() > before.length);
    }

    // ---- helpers ----

    private static void writeFile(Path file, int players, long updated, boolean folded) throws Exception {
        MemoryStore writer = new MemoryStore();
        for (int p = 0; p < players; p++) {
            UUID player = new UUID(0, p + 1);
            for (int c = 0; c < 2; c++) {
                writer.append(player, "npc" + c, "user", LONG_TEXT + " " + p, updated, 8, 8_000, 0L);
                writer.append(player, "npc" + c, "assistant", "short reply " + c, updated, 8, 8_000, 0L);
            }
        }
        writer.save(file.toFile(), null, false);
        if (folded) {
            YamlConfiguration plain = new YamlConfiguration();
            plain.load(file.toFile());
            plain.save(file.toFile());
        }
    }

    private static DialogueSettings persisting(boolean summaries) {
        DialogueSettings d = DialogueSettings.defaults();
        return new DialogueSettings(d.dialogueEnabled(), d.memoryTurns(), true, d.memoryMaxChars(), 168,
                d.sessionTimeoutSeconds(), d.leaveRadius(), d.maxRepliesPerSession(), d.messageCooldownMillis(),
                d.conversationsPerPlayerPerDay(), d.maxMessageLength(), d.cacheGreeting(), d.greetingCacheSeconds(),
                d.actionsEnabled(), d.actionLog(), d.maxActionsPerReply(), summaries, d.summaryThresholdTurns(),
                d.summaryMaxChars(), d.summaryMaxTokens(), d.summaryProvider(), d.summaryModel());
    }

    private static Fixture fixture(boolean summaries) throws Exception {
        Path file = Files.createTempDirectory("save-changes").resolve("dialogue-memory.yml");
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        AtomicLong clock = new AtomicLong(T0 + 1_000L);
        DialogueSettings settings = persisting(summaries);
        DialogueMemoryPersistence files = new DialogueMemoryPersistence(
                store, file::toFile, () -> settings, List::of, logger(records), clock::get);
        return new Fixture(file, store, files, records, clock);
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getLogger("save-changes-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
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
        });
        return logger;
    }

    private static Runnable waiting(CountDownLatch inside, CountDownLatch release) {
        return () -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitFinished(DialogueMemoryPersistence files) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!files.loadFinished()) {
            if (System.nanoTime() > deadline) {
                fail("loader did not finish");
            }
            Thread.sleep(5);
        }
    }

    private static void joinLoader(DialogueMemoryPersistence files) throws InterruptedException {
        Thread pending = files.diskLoader();
        if (pending != null) {
            pending.join(15_000L);
        }
    }

    private static long millisSince(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static void assertNoTempFile(Path dir) throws Exception {
        try (Stream<Path> list = Files.list(dir)) {
            assertTrue(list.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "temporary file left");
        }
    }

    private static void assertNoWarnings(List<LogRecord> records) {
        assertTrue(records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()),
                records.stream().map(LogRecord::getMessage).toList().toString());
    }

    private static YamlConfiguration parse(Path file) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        return yaml;
    }

    private static int entryCount(YamlConfiguration yaml) {
        var entries = yaml.getConfigurationSection("entries");
        if (entries == null) {
            return 0;
        }
        int n = 0;
        for (String player : entries.getKeys(false)) {
            var section = entries.getConfigurationSection(player);
            n += section == null ? 0 : section.getKeys(false).size();
        }
        return n;
    }

    private record Fixture(Path file, MemoryStore store, DialogueMemoryPersistence files,
                           List<LogRecord> records, AtomicLong clock) {
    }
}
