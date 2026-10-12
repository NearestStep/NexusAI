package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.config.SecretMask;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Writes {@link TokenLedger} to {@code token-usage.yml} on {@code nexusai-scheduler}.
 * Saves are skipped when nothing changed and never run more often than the configured interval,
 * except {@link #flush()} on reload and shutdown. A crash keeps the previous file; at most one
 * interval of counts is lost. A file that cannot be parsed is renamed aside and counters start at zero.
 */
public final class TokenLedgerStore {

    static final long WARN_GAP_MILLIS = 10L * 60L * 1000L;
    private static final Pattern STALE_TEMP = Pattern.compile("^token-usage\\.yml\\..+\\.tmp$");
    private static final DateTimeFormatter CORRUPT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path file;
    private final ScheduledExecutorService scheduler;
    private final TokenLedger ledger;
    private final Logger logger;
    private final Supplier<OffsetDateTime> updated;
    private final LongSupplier clockMillis;
    private final Object scheduleLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile Supplier<Iterable<String>> secretSource = List::of;
    private volatile int intervalSeconds = 10;
    private volatile long lastSaveMillis;
    private volatile long lastWarnMillis = Long.MIN_VALUE;
    private ScheduledFuture<?> task;

    public TokenLedgerStore(
            Path file,
            ScheduledExecutorService scheduler,
            TokenLedger ledger,
            Logger logger,
            Supplier<OffsetDateTime> updated,
            LongSupplier clockMillis
    ) {
        this.file = file;
        this.scheduler = scheduler;
        this.ledger = ledger == null ? new TokenLedger(null, logger) : ledger;
        this.logger = logger == null ? Logger.getLogger("nexusai.tokens") : logger;
        ZoneId zone = ZoneId.systemDefault();
        this.updated = updated == null ? () -> OffsetDateTime.now(zone) : updated;
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
    }

    public TokenLedger ledger() {
        return ledger;
    }

    public void secrets(Supplier<Iterable<String>> secrets) {
        this.secretSource = secrets == null ? List::of : secrets;
    }

    /**
     * Deletes leftover temp files, loads {@code token-usage.yml}, and starts the periodic save.
     * Call once, on the scheduler or before requests are accepted.
     */
    public void start(MissingUsage mode, int intervalSeconds) {
        this.intervalSeconds = clampInterval(intervalSeconds);
        ledger.missingUsage(mode);
        sweepTemps();
        if (load()) {
            dirty.set(true);
            // No scheduler (tests) writes here. Production defers to nexusai-scheduler.
            if (scheduler == null) {
                flush();
            }
        }
        reschedule();
    }

    /** Updates the estimate mode and the save interval. Counters are kept. */
    public void reconfigure(MissingUsage mode, int intervalSeconds) {
        ledger.missingUsage(mode);
        int next = clampInterval(intervalSeconds);
        if (next == this.intervalSeconds && task != null) {
            return;
        }
        this.intervalSeconds = next;
        reschedule();
    }

    public void record(ResponseUsage usage, CallTrace trace, String providerId, String storageKey, boolean fallbackSlot) {
        long before = ledger.mutations();
        ledger.record(usage, trace, providerId, storageKey, fallbackSlot);
        if (ledger.mutations() != before) {
            dirty.set(true);
        }
    }

    /** Writes when the interval has elapsed and a count changed. Runs on the scheduler. */
    public void saveIfDue() {
        if (!dirty.get()) {
            return;
        }
        long now = clockMillis.getAsLong();
        if (now - lastSaveMillis < intervalSeconds * 1000L) {
            return;
        }
        flush();
    }

    /** Writes immediately when something changed. Safe to call from the scheduler or from shutdown. */
    public void flush() {
        if (!dirty.get() || file == null) {
            return;
        }
        long seen = ledger.mutations();
        try {
            write(ledger.snapshot(), updated.get());
            if (ledger.mutations() == seen) {
                dirty.set(false);
            }
            lastSaveMillis = clockMillis.getAsLong();
        } catch (IOException e) {
            dirty.set(true);
            warnSave(e);
        }
    }

    private boolean load() {
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            TokenLedger.Snapshot stored = TokenUsageFile.parse(Files.readString(file, StandardCharsets.UTF_8));
            java.time.LocalDate fileDay = stored.day();
            ledger.restore(stored);
            ledger.catchUp();
            return !ledger.snapshot().day().equals(fileDay);
        } catch (TokenUsageFile.CorruptFile e) {
            quarantine(e.getMessage() == null ? "unreadable" : e.getMessage());
            return false;
        } catch (IOException e) {
            quarantine(e.getClass().getSimpleName());
            return false;
        }
    }

    private void write(TokenLedger.Snapshot snapshot, OffsetDateTime when) throws IOException {
        byte[] bytes = TokenUsageFile.render(snapshot, when, secrets()).getBytes(StandardCharsets.UTF_8);
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        Files.createDirectories(parent);
        Path temporary = parent.resolve("token-usage.yml." + UUID.randomUUID() + ".tmp");
        try {
            AtomicFiles.createPrivate(temporary);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            AtomicFiles.durableReplace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void quarantine(String reason) {
        String safeReason = reason == null ? "unreadable" : reason.replace('\r', ' ').replace('\n', ' ');
        OffsetDateTime when = updated.get();
        String stamp = when == null ? "unknown" : CORRUPT_STAMP.format(when);
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        Path destination = parent.resolve("token-usage.yml.corrupt." + stamp);
        int extra = 2;
        while (Files.exists(destination) && extra < 100) {
            destination = parent.resolve("token-usage.yml.corrupt." + stamp + "-" + extra);
            extra++;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            byte[] masked = SecretMask.redact(text, secrets()).getBytes(StandardCharsets.UTF_8);
            writeAside(destination, masked);
            Files.deleteIfExists(file);
            logger.warning("token-usage.yml could not be read (" + safeReason
                    + ") and was renamed to " + destination.getFileName()
                    + ". Today's counters start at zero.");
        } catch (IOException e) {
            warnSave(e);
            logger.warning("token-usage.yml could not be read (" + safeReason
                    + "). It was left in place and today's counters start at zero.");
        }
    }

    private static void writeAside(Path destination, byte[] bytes) throws IOException {
        Path parent = destination.getParent() == null ? Path.of(".") : destination.getParent();
        Path temporary = parent.resolve("token-usage.yml." + UUID.randomUUID() + ".tmp");
        try {
            AtomicFiles.createPrivate(temporary);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            AtomicFiles.durableReplace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void sweepTemps() {
        if (file == null) {
            return;
        }
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        if (!Files.isDirectory(parent)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream) {
                if (!Files.isRegularFile(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (!STALE_TEMP.matcher(name).matches()) {
                    continue;
                }
                try {
                    Files.deleteIfExists(child);
                } catch (IOException e) {
                    logger.warning("Could not remove stale temp file " + name);
                }
            }
        } catch (IOException e) {
            warnSave(e);
        }
    }

    private void reschedule() {
        if (scheduler == null) {
            return;
        }
        synchronized (scheduleLock) {
            if (task != null) {
                task.cancel(false);
            }
            long seconds = Math.max(1L, intervalSeconds);
            task = scheduler.scheduleWithFixedDelay(this::saveIfDue, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    private void warnSave(IOException error) {
        long now = clockMillis.getAsLong();
        if (lastWarnMillis != Long.MIN_VALUE) {
            long elapsed = now - lastWarnMillis;
            if (elapsed >= 0L && elapsed < WARN_GAP_MILLIS) {
                return;
            }
        }
        lastWarnMillis = now;
        LogRedaction.warning(logger, "Failed to save token-usage.yml", error, secrets());
    }

    private Iterable<String> secrets() {
        Iterable<String> values = secretSource.get();
        return values == null ? List.of() : values;
    }

    static int clampInterval(int seconds) {
        if (seconds < 1) {
            return 1;
        }
        return Math.min(seconds, 300);
    }
}
