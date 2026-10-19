package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.DumperOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saves of dialogue memory whose text holds characters YAML treats as line breaks (U+2028, U+2029,
 * U+0085) or other controls. Paper 26.2 ships SnakeYAML 2.6, older Paper and Folia ship 2.2; the
 * {@code testSnakeYaml22} and {@code testSnakeYaml26} tasks run this class on each of them.
 */
class YamlLineSeparatorTest {

    private static final long NOW = 1_000L;

    /** Text that has to be escaped, next to text that has to stay as it is. */
    private static final List<String> TEXTS = List.of(
            "First\u2028second",
            "First\u2029second",
            "First\u0085second",
            "First\rsecond",
            "bell\u0007and del\u007f",
            "hello",
            "true",
            "123",
            "",
            " lead and trail ",
            "a: b # c",
            "'single' and \"double\"",
            "tab\there",
            "line one\nline two",
            "line one\nline two\u2028three",
            "\u00e9t\u00e9 \u043f\u0440\u0438\u0432\u0435\u0442 \uD83D\uDE00");

    @AfterEach
    void clearHooks() {
        MemoryStore.beforeDocumentDump = null;
        MemoryStore.afterStopCount = null;
    }

    @Test
    void snakeYamlOnTheClasspathIsTheOneThisTaskExpects() throws IOException {
        String expected = System.getProperty("nexusai.test.snakeyaml");
        String actual = snakeYamlVersion();
        assertNotNull(actual, "SnakeYAML version not found on the test classpath");
        if (expected != null && !expected.isBlank()) {
            assertEquals(expected, actual);
        }
    }

    @Test
    void saveEscapesLineSeparatorsAndReadsEveryTextBack() throws Exception {
        Path file = tempFile("yaml-ls-save");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        for (int i = 0; i < TEXTS.size(); i++) {
            store.append(player, "npc" + i, i % 2 == 0 ? "user" : "assistant", TEXTS.get(i), NOW, 64, 1_000_000, 0L);
        }
        store.append(player, "harbor", "user", "plain", NOW, 64, 1_000_000, 0L);
        List<LogRecord> records = new ArrayList<>();
        store.save(file.toFile(), logger(records), false);

        assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + records);
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertNoRawSeparators(saved);
        assertTrue(saved.contains("\\L"), saved);
        assertTrue(saved.contains("\\P"), saved);
        assertTrue(saved.contains("\\N"), saved);
        assertTrue(saved.contains("\\r"), saved);
        assertTrue(saved.contains("text: hello"), saved);
        assertTrue(saved.contains("text: 'true'"), saved);
        assertTrue(saved.contains("text: '123'"), saved);
        assertEquals(0, warnings(records).size(), records.toString());

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), NOW, 0L, logger(records));
        for (int i = 0; i < TEXTS.size(); i++) {
            List<TurnMemory.Line> lines = loaded.transcript(player, "npc" + i, NOW, 64, 1_000_000, 0L);
            assertEquals(1, lines.size(), "npc" + i + "\n" + saved);
            assertEquals(TEXTS.get(i), lines.getFirst().text(), "npc" + i + "\n" + saved);
        }
        assertEquals("plain", loaded.transcript(player, "harbor", NOW, 64, 1_000_000, 0L).getFirst().text());
    }

    @Test
    void summaryWithANextLineCharacterIsSaved() throws Exception {
        Path file = tempFile("yaml-ls-summary");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "scribe", "user", "hello", NOW, 64, 1_000_000, 0L, true);
        assertTrue(store.completeSummary(player, "scribe", "First\u0085second", NOW, store.get(player, "scribe").epoch()));
        List<LogRecord> records = new ArrayList<>();
        store.save(file.toFile(), logger(records), true);

        assertTrue(Files.isRegularFile(file), "dialogue-memory.yml was not written: " + records);
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertNoRawSeparators(saved);
        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), NOW, 0L, logger(records));
        assertEquals("First\u0085second", loaded.summary(player, "scribe", NOW, 0L), saved);
        assertEquals(0, warnings(records).size(), records.toString());
    }

    /** A raw U+2028 inside a quoted string is valid YAML; the next save keeps old and new characters. */
    @Test
    void fileWithARawLineSeparatorKeepsANewCharacterOnSaveAndStop() throws Exception {
        for (boolean stop : new boolean[] {false, true}) {
            Path file = tempFile("yaml-ls-raw");
            UUID player = UUID.randomUUID();
            Files.writeString(file, """
                    entries:
                      %s:
                        smith:
                          updated: 50
                          lines:
                          - role: user
                            text: "Need\u2028something forged?"
                    """.formatted(player), StandardCharsets.UTF_8);
            List<LogRecord> records = new ArrayList<>();
            MemoryStore store = new MemoryStore();
            store.load(file.toFile(), NOW, 0L, logger(records));
            store.append(player, "harbor", "user", "new character", NOW, 64, 1_000_000, 0L);
            if (stop) {
                assertEquals(MemoryStore.StopSave.SAVED,
                        store.saveForStop(file.toFile(), logger(records), false, Long.MAX_VALUE));
            } else {
                store.save(file.toFile(), logger(records), false);
            }
            String saved = Files.readString(file, StandardCharsets.UTF_8);
            assertNoRawSeparators(saved);
            assertTrue(saved.contains("new character"), saved);
            MemoryStore loaded = new MemoryStore();
            loaded.load(file.toFile(), NOW, 0L, logger(records));
            assertEquals("Need\u2028something forged?",
                    loaded.transcript(player, "smith", NOW, 64, 1_000_000, 0L).getFirst().text(), saved);
            assertEquals("new character",
                    loaded.transcript(player, "harbor", NOW, 64, 1_000_000, 0L).getFirst().text(), saved);
            assertEquals(0, warnings(records).size(), records.toString());
        }
    }

    /** Masking a key on load rewrites the file; that rewrite escapes line separators too. */
    @Test
    void maskingRewriteDoesNotWriteRawLineSeparators() throws Exception {
        String key = "sk-test-mask-0123456789abcdef";
        Path file = tempFile("yaml-ls-mask");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                entries:
                  %s:
                    smith:
                      updated: 50
                      lines:
                      - role: user
                        text: "Key %s First\\Lsecond"
                      - role: assistant
                        text: "Next\\Nline and\\Pparagraph"
                """.formatted(player, key), StandardCharsets.UTF_8);
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), NOW, 0L, logger(records), List.of(key));

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(rewritten.contains(key), rewritten);
        assertNoRawSeparators(rewritten);
        assertTrue(records.stream().anyMatch(r -> "Masked API keys in dialogue-memory.yml".equals(r.getMessage())),
                records.toString());
        assertEquals(0, warnings(records).size(), records.toString());
        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), NOW, 0L, logger(records));
        List<TurnMemory.Line> lines = loaded.transcript(player, "smith", NOW, 64, 1_000_000, 0L);
        assertTrue(lines.get(0).text().endsWith(" First\u2028second"), rewritten);
        assertFalse(lines.get(0).text().contains(key), rewritten);
        assertEquals("Next\u0085line and\u2029paragraph", lines.get(1).text(), rewritten);
    }

    /**
     * A document the YAML library rejects is a warning on every periodic save that fails, with the
     * number of characters not saved, and the file is kept. The next save that works writes it.
     */
    @Test
    void failedDumpOnAPeriodicSaveIsAWarningNotSilence() throws Exception {
        Path file = tempFile("yaml-ls-dump-fails");
        UUID player = UUID.randomUUID();
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.append(player, "npc", "user", "First\u2028second", NOW, 64, 1_000_000, 0L);
        DialogueMemoryPersistence files = persistence(store, file, logger(records));
        files.loadedAtStartup();
        AtomicBoolean fail = new AtomicBoolean(true);
        MemoryStore.beforeDocumentDump = document -> {
            if (fail.get()) {
                throw new IllegalStateException("dump rejected for the test");
            }
        };

        files.save();
        assertFalse(Files.exists(file));
        List<String> warned = messages(warnings(records));
        assertEquals(2, warned.size(), warned.toString());
        assertEquals("Failed to save dialogue-memory.yml (IllegalStateException: dump rejected for the test)", warned.get(0));
        assertTrue(warned.get(1).startsWith("Did not save 1 dialogue character because "), warned.toString());

        fail.set(false);
        files.save();
        assertTrue(Files.isRegularFile(file));
        assertEquals(2, warnings(records).size(), records.toString());
        byte[] saved = Files.readAllBytes(file);
        fail.set(true);
        store.append(player, "npc", "user", "more", NOW + 1, 64, 1_000_000, 0L);
        files.save();
        assertEquals(4, warnings(records).size(), records.toString());
        assertTrue(java.util.Arrays.equals(saved, Files.readAllBytes(file)));
    }

    /** Stop with a document the YAML library rejects names what it did not save. */
    @Test
    void failedDumpAtStopWarnsWithTheUnsavedCount() throws Exception {
        Path file = tempFile("yaml-ls-dump-fails-stop");
        UUID player = UUID.randomUUID();
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.append(player, "npc", "user", "First\u2028second", NOW, 64, 1_000_000, 0L);
        store.append(player, "harbor", "user", "hello", NOW, 64, 1_000_000, 0L);
        DialogueMemoryPersistence files = persistence(store, file, logger(records));
        files.loadedAtStartup();
        MemoryStore.beforeDocumentDump = document -> {
            throw new IllegalStateException("dump rejected for the test");
        };
        files.shutdown();
        assertFalse(Files.exists(file));
        List<String> warned = messages(warnings(records));
        assertTrue(warned.contains("Failed to save dialogue-memory.yml (IllegalStateException: dump rejected for the test)"),
                warned.toString());
        assertTrue(warned.stream().anyMatch(line -> line.startsWith("Did not save 2 dialogue characters because ")), warned.toString());
    }

    /** A save that throws past the store is a warning, not a FINE line. */
    @Test
    void saveThatThrowsAtStopIsAWarning() throws Exception {
        Path file = tempFile("yaml-ls-stop-throws");
        UUID player = UUID.randomUUID();
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.append(player, "npc", "user", "hello", NOW, 64, 1_000_000, 0L);
        DialogueMemoryPersistence files = persistence(store, file, logger(records));
        files.loadedAtStartup();
        MemoryStore.afterStopCount = () -> {
            throw new IllegalStateException("stop failed for the test");
        };
        files.shutdown();
        assertEquals(List.of("Skipped dialogue memory save (IllegalStateException: stop failed for the test)"),
                messages(warnings(records)));
    }

    /** A masking rewrite that cannot be dumped leaves the file as it was and warns. */
    @Test
    void failedDumpOfAMaskingRewriteKeepsTheFileAndWarns() throws Exception {
        String key = "sk-test-mask-0123456789abcdef";
        Path file = tempFile("yaml-ls-mask-fails");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                entries:
                  %s:
                    smith:
                      updated: 50
                      lines:
                      - role: user
                        text: "Key %s First\\Lsecond"
                """.formatted(player, key), StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        MemoryStore.beforeDocumentDump = document -> {
            throw new IllegalStateException("dump rejected for the test");
        };
        List<LogRecord> records = new ArrayList<>();
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), NOW, 0L, logger(records), List.of(key));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
        List<String> warned = messages(warnings(records));
        assertEquals(List.of("Failed to save dialogue-memory.yml (IllegalStateException: dump rejected for the test)"), warned);
        assertFalse(warned.toString().contains(key));
    }

    private static DialogueMemoryPersistence persistence(MemoryStore store, Path file, Logger logger) {
        DialogueSettings d = DialogueSettings.defaults();
        DialogueSettings settings = new DialogueSettings(
                d.dialogueEnabled(), d.memoryTurns(), true, d.memoryMaxChars(), d.memoryExpiryHours(),
                d.sessionTimeoutSeconds(), d.leaveRadius(), d.maxRepliesPerSession(), d.messageCooldownMillis(),
                d.conversationsPerPlayerPerDay(), d.maxMessageLength(), d.cacheGreeting(), d.greetingCacheSeconds(),
                d.actionsEnabled(), d.actionLog(), d.maxActionsPerReply(), false, d.summaryThresholdTurns(),
                d.summaryMaxChars(), d.summaryMaxTokens(), d.summaryProvider(), d.summaryModel(), true);
        return new DialogueMemoryPersistence(store, file::toFile, () -> settings, List::of, logger, () -> NOW);
    }

    private static List<String> messages(List<LogRecord> records) {
        List<String> out = new ArrayList<>();
        for (LogRecord record : records) {
            out.add(record.getMessage());
        }
        return out;
    }

    private static void assertNoRawSeparators(String text) {
        for (char c : new char[] {'\u2028', '\u2029', '\u0085', '\r'}) {
            assertEquals(-1, text.indexOf(c), "raw U+" + String.format("%04X", (int) c) + " in\n" + text);
        }
    }

    static String snakeYamlVersion() throws IOException {
        try (InputStream in = DumperOptions.class.getResourceAsStream(
                "/META-INF/maven/org.yaml/snakeyaml/pom.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        }
    }

    private static Path tempFile(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).resolve("dialogue-memory.yml");
    }

    private static List<LogRecord> warnings(List<LogRecord> records) {
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord record : records) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                out.add(record);
            }
        }
        return out;
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getLogger("yaml-ls-" + UUID.randomUUID());
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
