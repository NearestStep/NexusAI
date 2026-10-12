package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.moderation.ModerationLog;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolStore;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * NAI-72. A data directory named with a configured key and a vendor key must not reach a
 * WARNING stack, and the exception class stays readable.
 */
class DataFileLogRegressionTest {

    private static final String CONFIGURED = "sk-qaConfiguredKey1111";
    private static final String VENDOR = "sk-qaUnconfigured9999zz";
    private static final String OTHER = "qa-ring-kA-0002y";

    @Test
    void poolSaveWarningMasksKeysInThePath() throws Exception {
        Path data = keyedData();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger("pool-keypath", records);
        try {
            lock(data);
            PoolStore store = new PoolStore(data.resolve("pool.yml").toFile(), scheduler, Duration.ofMillis(50), logger, true);
            store.secrets(() -> List.of(CONFIGURED, OTHER));
            store.saveNow(new AiPool(), Map.of());
            assertMaskedWarning(records, "Failed to save answer pool to pool.yml");
        } finally {
            unlock(data);
            scheduler.shutdownNow();
        }
    }

    @Test
    void dialogueMemorySaveAndBackupWarningsMaskKeysInThePath() throws Exception {
        Path data = keyedData();
        Path file = data.resolve("dialogue-memory.yml");
        Files.writeString(file, "entries: {}\n", StandardCharsets.UTF_8);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger("memory-keypath", records);
        MemoryStore store = new MemoryStore();
        store.secrets(() -> List.of(CONFIGURED, OTHER));
        store.append(UUID.randomUUID(), "blacksmith", "user", "hello", 10L, 8, 100, 0L);
        try {
            lock(data);
            store.save(file.toFile(), logger, false);
            assertMaskedWarning(records, "Failed to save dialogue-memory.yml");

            records.clear();
            store.save(file.toFile(), logger, true);
            assertMaskedWarning(records, "Failed to back up dialogue-memory.yml");
        } finally {
            unlock(data);
        }
    }

    @Test
    void dialogueMemoryRewriteAndLoadWarningsMaskKeysInThePath() throws Exception {
        Path data = keyedData();
        Path file = data.resolve("dialogue-memory.yml");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: "%s"
                """.formatted(player, VENDOR), StandardCharsets.UTF_8);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger("memory-rewrite", records);
        try {
            lock(data);
            MemoryStore store = new MemoryStore();
            store.secrets(() -> List.of(CONFIGURED, OTHER));
            store.load(file.toFile(), 60L, 10_000L, logger, List.of(CONFIGURED, OTHER));
            assertMaskedWarning(records, "Failed to save dialogue-memory.yml");
            assertTrue(records.stream().filter(record -> record.getLevel() == Level.WARNING)
                    .noneMatch(record -> record.getMessage() != null && record.getMessage().contains(VENDOR)));
        } finally {
            unlock(data);
        }

        records.clear();
        String path = data.resolve("dialogue-memory.yml").toString();
        DialogueService.logMemoryLoadFailure(logger, new AccessDeniedException(path), List.of(CONFIGURED, OTHER));
        assertMaskedWarning(records, "Failed to load dialogue-memory.yml");
    }

    @Test
    void usageModerationAndActionsWarningsMaskKeysInThePath() throws Exception {
        Path data = keyedData();
        List<LogRecord> records = new ArrayList<>();
        Logger logger = capturingLogger("usage-keypath", records);
        try {
            lock(data);
            File usage = data.resolve("usage.yml").toFile();
            ModelQueue queue = new ModelQueue(List.of(), 0, 1_000L, 1_000L, usage, logger);
            queue.secrets(() -> List.of(CONFIGURED, OTHER));
            queue.save();
            assertMaskedWarning(records, "Failed to save usage counters");

            records.clear();
            ModerationLog moderation = new ModerationLog(data.resolve("moderation.log").toFile(), logger);
            moderation.secrets(() -> List.of(CONFIGURED, OTHER));
            moderation.append(UUID.randomUUID(), "Steve", "hi", "spam", "test");
            assertMaskedWarning(records, "Failed to write moderation.log");

            records.clear();
            ActionLog actions = new ActionLog(logger, data.resolve("actions.log").toFile(), () -> true);
            actions.secrets(() -> List.of(CONFIGURED, OTHER));
            actions.record("Steve", "blacksmith", "wave", "ran");
            assertMaskedWarning(records, "Failed to append actions.log");
        } finally {
            unlock(data);
        }
    }

    private static void assertMaskedWarning(List<LogRecord> records, String summary) {
        List<LogRecord> warnings = records.stream().filter(record -> record.getLevel() == Level.WARNING).toList();
        assertFalse(warnings.isEmpty(), records.toString());
        boolean found = false;
        for (LogRecord record : warnings) {
            String message = record.getMessage() == null ? "" : record.getMessage();
            if (!message.contains(summary)) {
                continue;
            }
            found = true;
            assertTrue(record.getThrown() == null, message);
            assertTrue(message.contains("AccessDeniedException"), message);
            assertFalse(message.contains(CONFIGURED), message);
            assertFalse(message.contains(VENDOR), message);
            assertFalse(message.contains(OTHER), message);
            assertTrue(message.contains("****1111"), message);
            assertTrue(message.contains("****99zz"), message);
            assertTrue(message.contains("****002y"), message);
            assertTrue(message.contains("****99zz_"), message);
            assertFalse(message.contains("****9zz_"), message);
        }
        assertTrue(found, records.toString());
        for (LogRecord record : records) {
            if (record.getLevel() != Level.FINE || record.getThrown() == null) {
                continue;
            }
            String stack = String.valueOf(record.getThrown());
            assertFalse(stack.contains(CONFIGURED), stack);
            assertFalse(stack.contains(VENDOR), stack);
            assertFalse(stack.contains(OTHER), stack);
            assertTrue(stack.contains("AccessDeniedException"), stack);
        }
    }

    private static Path keyedData() throws Exception {
        Path root = Files.createTempDirectory("nai-keypath");
        assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null, "POSIX permissions are not available");
        Path data = root.resolve("kp_" + CONFIGURED + "_" + VENDOR + "_" + OTHER).resolve("plugins").resolve("NexusAI");
        Files.createDirectories(data);
        return data;
    }

    private static void lock(Path data) throws Exception {
        Files.setPosixFilePermissions(data, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void unlock(Path data) throws Exception {
        if (data != null && Files.exists(data)) {
            Files.setPosixFilePermissions(data, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static Logger capturingLogger(String name, List<LogRecord> records) {
        Logger logger = Logger.getLogger(name + "-" + UUID.randomUUID());
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
        return logger;
    }
}
