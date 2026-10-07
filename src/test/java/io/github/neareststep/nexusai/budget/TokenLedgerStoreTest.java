package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenLedgerStoreTest {

    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final String CANARY = "sk-CanaryKey0123456789Ab";

    @TempDir
    Path dir;

    @Test
    void flushWritesAtomicallyAndANewFileIsOwnerReadWrite() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        AtomicLong clock = new AtomicLong(1_000_000L);
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), clock);
        store.secrets(() -> List.of(CANARY));
        store.start(MissingUsage.ESTIMATE, 10);
        store.record(ResponseUsage.reported(100, 20, 120, null), trace(RequestOrigin.PLACEHOLDER, PLAYER, "nexusai"), "openai", "0|openai|gpt-4o-mini", false);
        store.flush();
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, temps().size(), temps().toString());
        String text = Files.readString(file);
        assertTrue(text.contains("format: 1"), text);
        assertTrue(text.contains("\"0|openai|gpt-4o-mini\""), text);
        assertFalse(text.contains(CANARY), text);
        assertFalse(text.contains("http://"), text);
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view != null) {
            assertEquals(OWNER_ONLY, view.readAttributes().permissions());
        }
        TokenLedger.Snapshot loaded = TokenUsageFile.parse(text);
        assertEquals(120L, loaded.server().total());
        assertEquals(1L, loaded.rows().get("0|openai|gpt-4o-mini").requests());
    }

    @Test
    void anExistingFileKeepsItsMode() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        Files.writeString(file, TokenUsageFile.render(empty(LocalDate.of(2026, 10, 7)), updated(), List.of()));
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        Set<PosixFilePermission> shared = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(file, shared);
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), new AtomicLong(5_000L));
        store.start(MissingUsage.ESTIMATE, 10);
        store.record(ResponseUsage.reported(1, 0, 1, null), trace(RequestOrigin.TEST, null, "nexusai"), "openai", "0|openai|m", false);
        store.flush();
        assertEquals(shared, Files.getPosixFilePermissions(file));
        assertEquals(1L, TokenUsageFile.parse(Files.readString(file)).server().requests());
    }

    @Test
    void startupDeletesLeftoverTempFilesAndQuarantinesACorruptFile() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        Path stale = dir.resolve("token-usage.yml." + UUID.randomUUID() + ".tmp");
        Path usageTemp = dir.resolve("usage.yml." + UUID.randomUUID() + ".tmp");
        Files.writeString(stale, CANARY);
        Files.writeString(usageTemp, "keep");
        Files.writeString(file, "format: 2\nnote: " + CANARY + "\n");
        List<String> warnings = new ArrayList<>();
        Logger logger = capturing("token-corrupt", warnings);
        AtomicReference<OffsetDateTime> updated = new AtomicReference<>(
                OffsetDateTime.of(2026, 10, 7, 15, 4, 5, 0, ZoneOffset.UTC));
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 7), logger);
        TokenLedgerStore store = new TokenLedgerStore(file, null, ledger, logger, updated::get, () -> 0L);
        store.secrets(() -> List.of(CANARY));
        store.start(MissingUsage.ESTIMATE, 10);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(usageTemp));
        assertFalse(Files.exists(file));
        Path corrupt = dir.resolve("token-usage.yml.corrupt.20261007-150405");
        assertTrue(Files.isRegularFile(corrupt), listNames());
        String quarantined = Files.readString(corrupt);
        assertFalse(quarantined.contains(CANARY), quarantined);
        assertEquals(0L, ledger.snapshot().server().requests());
        assertTrue(warnings.stream().anyMatch(message -> message.contains("token-usage.yml could not be read")));
    }

    @Test
    void unreadableYamlStartsAtZero() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        Files.writeString(file, "[\n");
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), new AtomicLong(0L));
        store.start(MissingUsage.ESTIMATE, 10);
        assertEquals(0L, store.ledger().snapshot().server().requests());
        assertFalse(Files.exists(file));
        assertTrue(listNames().contains("token-usage.yml.corrupt."), listNames());
    }

    @Test
    void aStoredEarlierDayBecomesOneHistoryLine() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        TokenLedger yesterday = new TokenLedger(() -> LocalDate.of(2026, 10, 1), Logger.getLogger("yesterday"));
        yesterday.record(ResponseUsage.reported(9, 0, 9, null), trace(RequestOrigin.TALK, PLAYER, "nexusai"), "openai", "0|openai|m", false);
        Files.writeString(file, TokenUsageFile.render(yesterday.snapshot(), updated(), List.of()));
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), new AtomicLong(20_000L));
        store.start(MissingUsage.ESTIMATE, 10);
        TokenLedger.Snapshot snap = store.ledger().snapshot();
        assertEquals(LocalDate.of(2026, 10, 7), snap.day());
        assertEquals(0L, snap.server().requests());
        assertEquals(1, snap.history().size());
        assertEquals(LocalDate.of(2026, 10, 1), snap.history().getFirst().day());
        assertEquals(1L, snap.history().getFirst().requests());
        assertEquals(9L, snap.history().getFirst().total());
        String written = Files.readString(file);
        assertEquals(2, written.split("day:", -1).length - 1, written);
        assertTrue(written.contains("\"2026-10-07\""), written);
        assertTrue(written.contains("\"2026-10-01\""), written);
    }

    @Test
    void reconfigureKeepsCountersThatAreNotOnDiskYet() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        AtomicLong clock = new AtomicLong(50_000L);
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), clock);
        store.start(MissingUsage.ESTIMATE, 10);
        store.record(ResponseUsage.reported(4, 1, 5, null), trace(RequestOrigin.SUMMARY, PLAYER, "nexusai"), "openai", "0|openai|m", false);
        store.flush();
        store.record(ResponseUsage.reported(1, 0, 1, null), trace(RequestOrigin.SUMMARY, PLAYER, "nexusai"), "openai", "0|openai|m", false);
        store.reconfigure(MissingUsage.IGNORE, 30);
        assertEquals(2L, store.ledger().snapshot().server().requests());
        assertEquals(6L, store.ledger().snapshot().server().total());
        assertEquals(1L, TokenUsageFile.parse(Files.readString(file)).server().requests());

        TokenLedger reloaded = new TokenLedger(() -> LocalDate.of(2026, 10, 7), Logger.getLogger("reload"));
        TokenLedgerStore second = new TokenLedgerStore(file, null, reloaded, Logger.getLogger("reload"), TokenLedgerStoreTest::updated, clock::get);
        second.start(MissingUsage.ESTIMATE, 10);
        assertEquals(1L, reloaded.snapshot().server().requests());
        assertEquals(2L, store.ledger().snapshot().server().requests());
    }

    @Test
    void saveIfDueWaitsForTheIntervalAndOnlyWritesWhenDirty() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        AtomicLong clock = new AtomicLong(1_000_000L);
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), clock);
        store.start(MissingUsage.ESTIMATE, 10);
        store.saveIfDue();
        assertFalse(Files.exists(file));
        store.record(ResponseUsage.reported(1, 0, 1, null), trace(RequestOrigin.TEST, null, "nexusai"), "openai", "0|openai|m", false);
        store.flush();
        long first = Files.getLastModifiedTime(file).toMillis();
        String firstText = Files.readString(file);
        store.record(ResponseUsage.reported(1, 0, 1, null), trace(RequestOrigin.TEST, null, "nexusai"), "openai", "0|openai|m", false);
        store.saveIfDue();
        assertEquals(firstText, Files.readString(file));
        clock.addAndGet(10_000L);
        store.saveIfDue();
        TokenLedger.Snapshot snap = TokenUsageFile.parse(Files.readString(file));
        assertEquals(2L, snap.server().requests());
        assertTrue(Files.getLastModifiedTime(file).toMillis() >= first);
        assertEquals(0, temps().size());
    }

    @Test
    void aDiskFailureWarnsAtMostOncePerTenMinutesAndCountingContinues() throws Exception {
        Path blocked = dir.resolve("not-a-directory");
        Files.writeString(blocked, "x");
        Path file = blocked.resolve("token-usage.yml");
        AtomicLong clock = new AtomicLong(1_000_000L);
        List<String> warnings = new ArrayList<>();
        Logger logger = capturing("token-disk", warnings);
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 7), logger);
        TokenLedgerStore store = new TokenLedgerStore(file, null, ledger, logger, TokenLedgerStoreTest::updated, clock::get);
        store.start(MissingUsage.ESTIMATE, 10);
        CallTrace trace = trace(RequestOrigin.API, PLAYER, "Shop");
        store.record(ResponseUsage.reported(1, 0, 1, null), trace, "openai", "0|openai|m", false);
        store.flush();
        store.record(ResponseUsage.reported(1, 0, 1, null), trace, "openai", "0|openai|m", false);
        store.flush();
        long saveWarnings = warnings.stream().filter(message -> message.contains("Failed to save token-usage.yml")).count();
        assertEquals(1L, saveWarnings, warnings.toString());
        assertEquals(2L, ledger.snapshot().server().requests());
        clock.addAndGet(TokenLedgerStore.WARN_GAP_MILLIS);
        store.flush();
        saveWarnings = warnings.stream().filter(message -> message.contains("Failed to save token-usage.yml")).count();
        assertEquals(2L, saveWarnings, warnings.toString());
        assertTrue(warnings.stream().noneMatch(message -> message.contains(CANARY)));
    }

    @Test
    void eightThreadsPersistTheSameTotals() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        TokenLedgerStore store = store(file, null, LocalDate.of(2026, 10, 7), new AtomicLong(80_000L));
        store.secrets(() -> List.of(CANARY));
        store.start(MissingUsage.ESTIMATE, 10);
        int threads = 8;
        int each = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "token-store-race");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        ready.await();
                        for (int n = 0; n < each; n++) {
                            store.record(
                                    ResponseUsage.reported(1, 1, 2, null),
                                    trace(RequestOrigin.PLACEHOLDER, PLAYER, "nexusai"),
                                    "openai",
                                    "0|openai|gpt",
                                    false);
                            if (n % 20 == 0) {
                                store.flush();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        store.flush();
        assertEquals(0, temps().size(), temps().toString());
        String text = Files.readString(file);
        assertFalse(text.contains(CANARY), text);
        TokenLedger.Snapshot snap = TokenUsageFile.parse(text);
        assertEquals(threads * (long) each, snap.server().requests());
        assertEquals(threads * (long) each * 2L, snap.server().total());
        assertEquals(snap.server(), store.ledger().snapshot().server());
    }

    @Test
    void aSchedulerDefersTheRolledDayUntilFlush() throws Exception {
        Path file = dir.resolve("token-usage.yml");
        TokenLedger yesterday = new TokenLedger(() -> LocalDate.of(2026, 10, 6), Logger.getLogger("defer"));
        yesterday.record(ResponseUsage.reported(2, 0, 2, null), trace(RequestOrigin.POOL, null, "nexusai"), "openai", "0|openai|m", false);
        String original = TokenUsageFile.render(yesterday.snapshot(), updated(), List.of());
        Files.writeString(file, original);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexusai-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        try {
            TokenLedgerStore store = store(file, scheduler, LocalDate.of(2026, 10, 7), new AtomicLong(90_000L));
            store.start(MissingUsage.ESTIMATE, 300);
            assertEquals(original, Files.readString(file));
            assertEquals(LocalDate.of(2026, 10, 7), store.ledger().snapshot().day());
            assertEquals(1, store.ledger().snapshot().history().size());
            store.flush();
            assertTrue(Files.readString(file).contains("\"2026-10-07\""));
        } finally {
            scheduler.shutdownNow();
        }
    }

    private TokenLedgerStore store(Path file, ScheduledExecutorService scheduler, LocalDate today, AtomicLong clock) {
        TokenLedger ledger = new TokenLedger(() -> today, Logger.getLogger("token-store"));
        return new TokenLedgerStore(file, scheduler, ledger, Logger.getLogger("token-store"), TokenLedgerStoreTest::updated, clock::get);
    }

    private static TokenLedger.Snapshot empty(LocalDate day) {
        return new TokenLedger.Snapshot(day, TokenLedger.Counts.zero(), null, null, null, null, null, null, List.of());
    }

    private static OffsetDateTime updated() {
        return OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private static CallTrace trace(RequestOrigin origin, UUID player, String consumer) {
        return CallTrace.start(origin, consumer, player, "prompt", "");
    }

    private List<Path> temps() throws Exception {
        List<Path> found = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().contains(".tmp")).forEach(found::add);
        }
        return found;
    }

    private String listNames() throws Exception {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.map(path -> path.getFileName().toString()).forEach(names::add);
        }
        return names.toString();
    }

    private static Logger capturing(String name, List<String> warnings) {
        Logger logger = Logger.getLogger(name + "-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                    warnings.add(record.getMessage());
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
