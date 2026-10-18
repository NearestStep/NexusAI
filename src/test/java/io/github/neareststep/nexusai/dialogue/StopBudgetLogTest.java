package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NAI-114: one INFO line when stop runs during the first dialogue memory load. */
class StopBudgetLogTest {

    private static final String PREFIX = "Stop ran during the first dialogue memory load";

    @AfterEach
    void reset() {
        MemoryStore.pauseDuringLoad = null;
        MemoryStore.beforeKeyScan = null;
        MemoryStore.abandonActiveLoad = false;
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
                        + "1 dialogue characters held in memory: appended 0, already in file 0.",
                DialogueMemoryPersistence.budgetStopLine(5_000L, 1L, 0L, 0, 0, 1));
        assertTrue(DialogueMemoryPersistence.budgetStopLine(5_000L, 0L, 0L, 0, 0, 0).contains(" 0 KiB "));
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
            assertTrue(inside.await(5, TimeUnit.SECONDS));
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
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.files.shutdown();
            assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(fixture.file));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().endsWith("1 dialogue characters held in memory: appended 0, already in file 1."),
                    lines.getFirst());
            assertEquals(0, fixture.records.stream()
                    .filter(r -> r.getLevel() == Level.WARNING && r.getMessage().startsWith("Did not save "))
                    .count(), fixture.records.toString());
        } finally {
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
                scanRelease.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        DialogueMemoryPersistence.shutdownAppendBudgetMillis = 200L;
        try {
            fixture.files.onReload();
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            fixture.files.shutdown();
            assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(fixture.file));
            List<String> lines = infos(fixture.records);
            assertEquals(1, lines.size(), fixture.records.toString());
            assertTrue(lines.getFirst().contains("used the stop budget: 200 ms for a "), lines.getFirst());
            assertTrue(lines.getFirst().endsWith("1 dialogue characters held in memory: appended 0, already in file 0."),
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
            loader.join(5_000L);
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
            assertTrue(inside.await(5, TimeUnit.SECONDS));
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
                if (!release.await(15, TimeUnit.SECONDS)) {
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
            pending.join(5_000L);
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
