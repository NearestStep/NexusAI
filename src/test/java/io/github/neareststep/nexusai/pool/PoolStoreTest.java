package io.github.neareststep.nexusai.pool;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolStoreTest {

    @Test
    void saveAndLoadRoundTripTrimsToTheConfiguredSize() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-store"), true);
            AiPool source = new AiPool();
            source.add("Say: \"hi\"", "one");
            source.add("Say: \"hi\"", "two");
            source.add("Say: \"hi\"", "three");
            source.add("other", "skip-me");
            store.saveNow(source, Map.of("Say: \"hi\"", 2));
            String saved = Files.readString(file.toPath());
            assertTrue(saved.contains("\"one\""));
            assertTrue(saved.contains("\"two\""));
            assertFalse(saved.contains("\n        "));

            AiPool loaded = new AiPool();
            store.load(loaded, Map.of("Say: \"hi\"", 2));
            assertEquals(List.of("one", "two"), loaded.copy("Say: \"hi\""));
            assertEquals(0, loaded.size("other"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void debouncedSaveWritesAfterTheQuietPeriod() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-debounce").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(150), Logger.getLogger("pool-store"), true);
            AiPool pool = new AiPool();
            pool.add("tip", "ready");
            store.markDirty(pool, Map.of("tip", 3));
            assertTrue(!file.isFile() || Files.readString(file.toPath()).isBlank() || !Files.readString(file.toPath()).contains("ready"));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                if (file.isFile() && Files.readString(file.toPath()).contains("ready")) {
                    return;
                }
                Thread.sleep(20);
            }
            assertTrue(file.isFile() && Files.readString(file.toPath()).contains("ready"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void invalidPoolFileIsNotOverwritten() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-broken").resolve("pool.yml").toFile();
        Files.writeString(file.toPath(), "pools: [\n  this is not yaml");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-store"), true);
            AiPool loaded = new AiPool();
            store.load(loaded, Map.of("tip", 3));
            assertEquals(0, loaded.size("tip"));
            AiPool replacement = new AiPool();
            replacement.add("tip", "should-not-land");
            store.saveNow(replacement, Map.of("tip", 3));
            assertEquals("pools: [\n  this is not yaml", Files.readString(file.toPath()));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void invalidPoolFileLogsOneWarningAndNoErrorStack() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-log").resolve("pool.yml").toFile();
        String original = "pools: [\n  this is not yaml";
        Files.writeString(file.toPath(), original);
        Logger logger = Logger.getLogger("pool-invalid-" + file.getParentFile().getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
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
        List<LogRecord> severe = new ArrayList<>();
        Handler rootWatch = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    severe.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        rootWatch.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        Logger bukkit = Logger.getLogger("Bukkit");
        root.addHandler(rootWatch);
        bukkit.addHandler(rootWatch);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), logger, true);
            assertEquals(List.of(), store.savedPrompts());
            AiPool loaded = new AiPool();
            store.load(loaded, Map.of("tip", 3));
            assertEquals(0, loaded.size("tip"));
            AiPool replacement = new AiPool();
            replacement.add("tip", "should-not-land");
            store.saveNow(replacement, Map.of("tip", 3));
            assertEquals(original, Files.readString(file.toPath()));

            List<LogRecord> warnings = records.stream()
                    .filter(record -> record.getLevel() == Level.WARNING)
                    .toList();
            assertEquals(1, warnings.size());
            LogRecord warning = warnings.getFirst();
            assertNull(warning.getThrown());
            String message = warning.getMessage();
            assertTrue(message.contains(file.getAbsolutePath()), message);
            assertTrue(message.contains("line"), message);
            assertTrue(message.contains("column"), message);
            assertTrue(message.contains("left untouched"), message);
            assertTrue(message.contains("empty until"), message);
            assertTrue(records.stream().noneMatch(record -> record.getLevel().intValue() >= Level.SEVERE.intValue()));
            assertTrue(records.stream().anyMatch(record -> record.getLevel() == Level.FINE && record.getThrown() != null));
            assertTrue(severe.isEmpty(), severe.toString());
        } finally {
            logger.removeHandler(handler);
            root.removeHandler(rootWatch);
            bukkit.removeHandler(rootWatch);
            scheduler.shutdownNow();
        }
    }

    @Test
    void multilinePromptRoundTrips() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-lines").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-store"), true);
            String prompt = "Stay fed\nSleep";
            AiPool source = new AiPool();
            source.add(prompt, "one");
            store.saveNow(source, Map.of(prompt, 2));

            AiPool loaded = new AiPool();
            store.load(loaded, Map.of(prompt, 2));
            assertEquals(List.of("one"), loaded.copy(prompt));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void disabledStoreDoesNotCreateAFile() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-off").resolve("pool.yml").toFile();
        PoolStore store = PoolStore.disabled();
        AiPool pool = new AiPool();
        pool.add("tip", "x");
        store.markDirty(pool, Map.of("tip", 1));
        store.flush(pool, Map.of("tip", 1));
        store.load(pool, Map.of("tip", 1));
        assertTrue(!file.isFile());
    }
}
