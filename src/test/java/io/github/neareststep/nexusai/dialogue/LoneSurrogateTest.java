package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lone UTF-16 surrogate cannot be written by SnakeYAML (2.2 or 2.6). Memory never keeps one:
 * cuts keep surrogate pairs whole, and a lone surrogate from any source becomes U+FFFD. The
 * {@code testSnakeYaml22} and {@code testSnakeYaml26} tasks run this class on each version.
 */
class LoneSurrogateTest {

    private static final long NOW = 1_000L;
    private static final String EMOJI = "\uD83D\uDE00";

    @AfterEach
    void clearHook() {
        MemoryStore.beforeDocumentDump = null;
    }

    /** "a😀cd" capped at 3 chars: the cut falls between the two halves of the emoji. */
    @Test
    void lengthCapDoesNotSplitAnEmoji() {
        TurnMemory memory = new TurnMemory();
        memory.add("assistant", "a" + EMOJI + "cd", NOW);
        memory.trim(8, 3, true);
        List<TurnMemory.Line> kept = memory.view();
        assertEquals(1, kept.size());
        assertEquals("cd", kept.getFirst().text());
        assertTrue(memory.chars() <= 3);
        List<TurnMemory.Line> folded = memory.pendingView();
        assertEquals(1, folded.size());
        assertEquals("a" + EMOJI, folded.getFirst().text());
    }

    /** The fold buffer has its own cap; that cut keeps the emoji whole too. */
    @Test
    void foldBufferCapDoesNotSplitAnEmoji() {
        TurnMemory memory = new TurnMemory();
        memory.add("user", "x" + EMOJI + "yz", NOW);
        memory.add("assistant", "ok", NOW);
        memory.trim(8, 3, true);
        assertEquals(List.of(new TurnMemory.Line("assistant", "ok")), memory.view());
        assertEquals(List.of(new TurnMemory.Line("user", "yz")), memory.pendingView());
    }

    /** An emoji the memory cap would cut in half is saved, and the other character too. */
    @Test
    void emojiAtTheMemoryCapIsSaved() throws Exception {
        Path file = tempFile("lone-cap");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "harbor", "assistant", "xxxxxxxxxx" + EMOJI + "tail", NOW, 8, 5, 0L);
        store.append(player, "smith", "user", "hello", NOW, 8, 8_000, 0L);
        List<LogRecord> records = new ArrayList<>();
        store.save(file.toFile(), logger(records), false);

        assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + messages(records));
        MemoryStore loaded = load(file, records);
        assertEquals("tail", text(loaded, player, "harbor"));
        assertEquals("hello", text(loaded, player, "smith"));
        assertEquals(List.of(), messages(warnings(records)));
    }

    /** A lone surrogate from a reply is stored as U+FFFD; the file is written for every character. */
    @Test
    void loneSurrogatesInRepliesAreSavedAsReplacementCharacters() throws Exception {
        Path file = tempFile("lone-reply");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "harbor", "assistant", "First\uD800second", NOW, 8, 8_000, 0L);
        store.append(player, "dock", "assistant", "end\uDC00", NOW, 8, 8_000, 0L);
        store.append(player, "mill", "assistant", "\uDE00\uD83D swapped", NOW, 8, 8_000, 0L);
        store.append(player, "smith", "user", "hello " + EMOJI, NOW, 8, 8_000, 0L);
        List<LogRecord> records = new ArrayList<>();
        store.save(file.toFile(), logger(records), false);

        assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + messages(records));
        MemoryStore loaded = load(file, records);
        assertEquals("First\uFFFDsecond", text(loaded, player, "harbor"));
        assertEquals("end\uFFFD", text(loaded, player, "dock"));
        assertEquals("\uFFFD\uFFFD swapped", text(loaded, player, "mill"));
        assertEquals("hello " + EMOJI, text(loaded, player, "smith"));
        assertEquals(List.of(), messages(warnings(records)));
    }

    @Test
    void summaryWithALoneSurrogateIsSaved() throws Exception {
        Path file = tempFile("lone-summary");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "scribe", "user", "hello", NOW, 8, 8_000, 0L, true);
        assertTrue(store.completeSummary(player, "scribe", "They said \uD83D", NOW, store.get(player, "scribe").epoch()));
        assertEquals("They said \uFFFD", store.summary(player, "scribe", NOW, 0L), "memory holds the lone surrogate");
        List<LogRecord> records = new ArrayList<>();
        store.save(file.toFile(), logger(records), true);

        assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + messages(records));
        MemoryStore loaded = load(file, records);
        assertEquals("They said \uFFFD", loaded.summary(player, "scribe", NOW, 0L));
        assertEquals(List.of(), messages(warnings(records)));
    }

    /** A file can hold an escaped lone surrogate. The next save writes it as U+FFFD and keeps a new character. */
    @Test
    void escapedLoneSurrogateInTheFileIsRepairedOnSaveAndStop() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            Path file = tempFile("lone-file");
            UUID player = UUID.randomUUID();
            Files.writeString(file, """
                    entries:
                      %s:
                        smith:
                          updated: 50
                          lines:
                          - role: user
                            text: "Need\\ud800something"
                    """.formatted(player), StandardCharsets.UTF_8);
            List<LogRecord> records = new ArrayList<>();
            MemoryStore store = load(file, records);
            store.append(player, "harbor", "user", "new character", NOW, 8, 8_000, 0L);
            if (stop) {
                assertEquals(MemoryStore.StopSave.SAVED,
                        store.saveForStop(file.toFile(), logger(records), false, Long.MAX_VALUE));
            } else {
                store.save(file.toFile(), logger(records), false);
            }
            MemoryStore loaded = load(file, records);
            assertEquals("Need\uFFFDsomething", text(loaded, player, "smith"), Files.readString(file));
            assertEquals("new character", text(loaded, player, "harbor"), Files.readString(file));
            assertEquals(List.of(), messages(warnings(records)));
        }
    }

    /** Masking a key on load rewrites the file; a lone surrogate in it does not stop that rewrite. */
    @Test
    void maskingRewriteWithALoneSurrogateMasksTheKey() throws Exception {
        String key = "sk-test-mask-0123456789abcdef";
        Path file = tempFile("lone-mask");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                entries:
                  %s:
                    smith:
                      updated: 50
                      lines:
                      - role: user
                        text: "Key %s and \\udc00"
                """.formatted(player, key), StandardCharsets.UTF_8);
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), NOW, 0L, logger(records), List.of(key));

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains(key), rewritten);
        assertEquals(List.of(), messages(warnings(records)));
        String stored = text(load(file, records), player, "smith");
        assertTrue(stored.endsWith(" and \uFFFD"), stored);
    }

    /**
     * A character YAML cannot write (the hook rejects any document that holds "broken") is left
     * out; the other characters are saved, by a periodic save and by stop, with one warning that
     * holds no dialogue text.
     */
    @Test
    void oneCharacterThatCannotBeWrittenDoesNotBlockTheOthers() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            Path file = tempFile("lone-broken");
            UUID player = UUID.randomUUID();
            MemoryStore store = new MemoryStore();
            store.append(player, "smith", "user", "hello", NOW, 8, 8_000, 0L);
            store.append(player, "broken", "assistant", "private words", NOW, 8, 8_000, 0L);
            store.append(player, "harbor", "user", "new character", NOW, 8, 8_000, 0L);
            MemoryStore.beforeDocumentDump = document -> {
                if (holdsKey(document.get("entries"), "broken")) {
                    throw new IllegalStateException("rejected for the test");
                }
            };
            List<LogRecord> records = new ArrayList<>();
            if (stop) {
                assertEquals(MemoryStore.StopSave.SAVED,
                        store.saveForStop(file.toFile(), logger(records), false, Long.MAX_VALUE));
            } else {
                store.save(file.toFile(), logger(records), false);
            }
            MemoryStore.beforeDocumentDump = null;

            assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + messages(records));
            String saved = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(saved.contains("broken"), saved);
            MemoryStore loaded = load(file, new ArrayList<>());
            assertEquals("hello", text(loaded, player, "smith"));
            assertEquals("new character", text(loaded, player, "harbor"));
            assertEquals(List.of("Left 1 dialogue character out of dialogue-memory.yml because YAML could not write them; "
                    + "the other characters were saved (IllegalStateException: rejected for the test)"),
                    messages(warnings(records)));
            assertTrue(messages(records).stream().noneMatch(line -> line.contains("private words")), messages(records).toString());
        }
    }

    /** Lines and summaries never hold a lone surrogate in memory; clean text stays the same instance. */
    @Test
    void memoryReplacesLoneSurrogatesAndKeepsCleanText() {
        assertEquals("a\uFFFDb", new TurnMemory.Line("user", "a\uD800b").text());
        String clean = "hi \uD83D\uDE00";
        assertSame(clean, new TurnMemory.Line("assistant", clean).text());
        TurnMemory memory = new TurnMemory();
        memory.load(List.of(new TurnMemory.Line("user", "x\uDC00")), NOW, "said \uDC00", NOW);
        assertEquals("said \uFFFD", memory.summary());
        assertEquals("x\uFFFD", memory.view().get(0).text());
    }

    /**
     * A string YAML cannot write that is not dialogue text (the hook adds one to the document
     * once) is replaced with U+FFFD on the retry; every character is still saved, with no warning.
     */
    @Test
    void loneSurrogateLeftInTheDocumentIsReplacedOnTheRetry() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            Path file = tempFile("lone-retry");
            UUID player = UUID.randomUUID();
            MemoryStore store = new MemoryStore();
            store.append(player, "smith", "user", "hello", NOW, 8, 8_000, 0L);
            AtomicBoolean first = new AtomicBoolean(true);
            MemoryStore.beforeDocumentDump = document -> {
                if (first.getAndSet(false)) {
                    document.set("note", "left \uD800");
                }
            };
            List<LogRecord> records = new ArrayList<>();
            if (stop) {
                assertEquals(MemoryStore.StopSave.SAVED,
                        store.saveForStop(file.toFile(), logger(records), false, Long.MAX_VALUE));
            } else {
                store.save(file.toFile(), logger(records), false);
            }
            MemoryStore.beforeDocumentDump = null;

            assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + messages(records));
            String saved = Files.readString(file, StandardCharsets.UTF_8);
            assertNoLoneSurrogate(saved);
            assertTrue(saved.contains("left \uFFFD") || saved.contains("left \\uFFFD"), saved);
            assertEquals("hello", text(load(file, new ArrayList<>()), player, "smith"));
            assertEquals(List.of(), messages(warnings(records)));
        }
    }

    /** Whether {@code key} names an entry anywhere under {@code value} (sections or plain maps). */
    static boolean holdsKey(Object value, String key) {
        if (value instanceof ConfigurationSection section) {
            for (String child : section.getKeys(false)) {
                if (child.equals(key) || holdsKey(section.get(child), key)) {
                    return true;
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (key.equals(entry.getKey()) || holdsKey(entry.getValue(), key)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void assertNoLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                continue;
            }
            assertFalse(Character.isSurrogate(c), "lone surrogate at " + i + " in " + escape(text));
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c < 0x7f && c >= 0x20 ? String.valueOf(c) : String.format("\\u%04X", (int) c));
        }
        return out.toString();
    }

    private static String text(MemoryStore store, UUID player, String id) {
        List<TurnMemory.Line> lines = store.transcript(player, id, NOW, 8, 1_000_000, 0L);
        assertEquals(1, lines.size(), id);
        return lines.getFirst().text();
    }

    private static MemoryStore load(Path file, List<LogRecord> records) {
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), NOW, 0L, logger(records));
        return store;
    }

    private static Path tempFile(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).resolve("dialogue-memory.yml");
    }

    static List<LogRecord> warnings(List<LogRecord> records) {
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                out.add(record);
            }
        }
        return out;
    }

    static List<String> messages(List<LogRecord> records) {
        List<String> out = new ArrayList<>();
        for (LogRecord record : records) {
            out.add(record.getMessage());
        }
        return out;
    }

    static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getLogger("lone-surrogate-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
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
        });
        return logger;
    }
}
