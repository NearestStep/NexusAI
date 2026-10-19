package io.github.neareststep.nexusai.pool;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NAI-132: a lone UTF-16 surrogate in one answer must not stop pool.yml from being written. */
class PoolLoneSurrogateTest {

    private static final String HALF = "\uD83D";

    @Test
    void poolStoresALoneSurrogateAsAReplacementCharacter() {
        AiPool pool = new AiPool();
        assertTrue(pool.add("tip", "half " + HALF + " emoji"));
        pool.replace("other", List.of("low \uDE00 only", "whole \uD83D\uDE00"));
        assertEquals(List.of("half \uFFFD emoji"), pool.copy("tip"));
        assertEquals(List.of("low \uFFFD only", "whole \uD83D\uDE00"), pool.copy("other"));
        assertEquals("half \uFFFD emoji", pool.poll("tip").orElseThrow());
    }

    /**
     * One answer holds a lone surrogate that did not go through the pool's cleaning (it is put
     * into the queue directly). The save still writes every prompt and answer, with U+FFFD for
     * that one, and logs no warning.
     */
    @Test
    void oneAnswerWithALoneSurrogateDoesNotBlockTheFile() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-pool-lone");
        File file = dir.resolve("pool.yml").toFile();
        List<LogRecord> records = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), logger(records), true);
            AiPool pool = new AiPool();
            pool.add("first", "clean one");
            pool.add("second", "clean two");
            rawQueue(pool, "second").addLast("broken " + HALF + " reply");
            pool.add("third", "clean three");
            Map<String, Integer> limits = new LinkedHashMap<>();
            limits.put("first", 5);
            limits.put("second", 5);
            limits.put("third", 5);

            store.saveNow(pool, limits);

            assertEquals(List.of(), messages(records, Level.WARNING));
            assertTrue(file.isFile(), "pool.yml was not written: " + messages(records, Level.ALL));
            String saved = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            assertTrue(saved.contains("\"broken \uFFFD reply\""), saved);
            AiPool loaded = new AiPool();
            store.load(loaded, limits);
            assertEquals(List.of("clean one"), loaded.copy("first"));
            assertEquals(List.of("clean two", "broken \uFFFD reply"), loaded.copy("second"));
            assertEquals(List.of("clean three"), loaded.copy("third"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** A pool.yml with an escaped lone surrogate loads as U+FFFD, and the next save writes it so. */
    @Test
    void escapedLoneSurrogateInTheFileIsRepairedOnSave() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-pool-escaped");
        File file = dir.resolve("pool.yml").toFile();
        String original = """
                config-version: 1
                pools:
                  - prompt: "tip"
                    answers:
                      - "kept"
                      - "half \\ud83d end"
                """;
        Files.writeString(file.toPath(), original, StandardCharsets.UTF_8);
        List<LogRecord> records = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), logger(records), true);
            AiPool pool = new AiPool();
            store.load(pool, Map.of("tip", 5));
            assertEquals(List.of("kept", "half \uFFFD end"), pool.copy("tip"));

            store.saveNow(pool, Map.of("tip", 5));

            assertEquals(List.of(), messages(records, Level.WARNING));
            assertEquals("""
                    config-version: 1
                    pools:
                      - prompt: "tip"
                        answers:
                          - "kept"
                          - "half \uFFFD end"
                    """, Files.readString(file.toPath(), StandardCharsets.UTF_8));
            assertEquals(original, Files.readString(dir.resolve("pool.yml.bak"), StandardCharsets.UTF_8),
                    "the file that changed is backed up first");
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** A pool without lone surrogates is written exactly as before, byte for byte. */
    @Test
    void cleanPoolIsWrittenByteForByteAsBefore() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-pool-clean");
        File file = dir.resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-clean"), true);
            store.saveNow(new AiPool(), Map.of("tip", 3));
            assertEquals("config-version: 1\npools: []\n", Files.readString(file.toPath(), StandardCharsets.UTF_8));

            AiPool pool = new AiPool();
            String quoted = "Say: \"hi\"";
            pool.add(quoted, "one");
            pool.add(quoted, "tab\there");
            pool.add(quoted, "back\\slash and \"quotes\"");
            pool.add(quoted, "line\nbreak");
            pool.add(quoted, "emoji \uD83D\uDE00 \u043F\u0440\u0438\u0432\u0435\u0442 \u00e9t\u00e9");
            String chat = PoolKeys.memory("chat", "tip");
            pool.add(chat, "ready");
            Map<String, Integer> limits = new LinkedHashMap<>();
            limits.put(quoted, 10);
            limits.put(chat, 10);
            store.saveNow(pool, limits);

            byte[] expected = ("config-version: 1\n"
                    + "pools:\n"
                    + "  - prompt: \"Say: \\\"hi\\\"\"\n"
                    + "    answers:\n"
                    + "      - \"one\"\n"
                    + "      - \"tab\\there\"\n"
                    + "      - \"back\\\\slash and \\\"quotes\\\"\"\n"
                    + "      - \"line\\nbreak\"\n"
                    + "      - \"emoji \uD83D\uDE00 \u043F\u0440\u0438\u0432\u0435\u0442 \u00e9t\u00e9\"\n"
                    + "  - prompt: \"tip\"\n"
                    + "    format: \"chat\"\n"
                    + "    answers:\n"
                    + "      - \"ready\"\n").getBytes(StandardCharsets.UTF_8);
            assertEquals(new String(expected, StandardCharsets.UTF_8),
                    Files.readString(file.toPath(), StandardCharsets.UTF_8));
            assertTrue(java.util.Arrays.equals(expected, Files.readAllBytes(file.toPath())));
            assertFalse(Files.exists(dir.resolve("pool.yml.bak")), "a clean file is not backed up");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedDeque<String> rawQueue(AiPool pool, String prompt) throws Exception {
        Field field = AiPool.class.getDeclaredField("pools");
        field.setAccessible(true);
        return ((ConcurrentHashMap<String, ConcurrentLinkedDeque<String>>) field.get(pool)).get(prompt);
    }

    private static List<String> messages(List<LogRecord> records, Level atLeast) {
        synchronized (records) {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= atLeast.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getLogger("pool-lone-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (records) {
                    records.add(record);
                }
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
        return logger;
    }
}
