package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.config.SecretMask;

import java.io.File;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads and saves {@code dialogue-memory.yml} across {@code /nai reload}.
 * The first off-to-on transition reads the file on {@code nexusai-memory-load} and no save runs
 * until that read has finished. A failed or hung read logs one warning and does not replace the file.
 */
final class DialogueMemoryPersistence {

    /** A load still running after this long is treated as stuck, and the next skipped save warns once. */
    static final long LOAD_HANG_WARNING_AFTER_MILLIS = 5_000L;

    /**
     * How long stop waits for a load that is about to finish. A longer read is not joined out:
     * characters that exist only in memory are appended, and characters already in the file stay there.
     */
    static long shutdownLoadGraceMillis = 1_000L;

    static final ThreadFactory DEFAULT_LOADER_THREADS = task -> {
        Thread worker = new Thread(task, "nexusai-memory-load");
        worker.setDaemon(true);
        return worker;
    };

    /**
     * Builds the loader thread and does not start it. The service assigns the thread before
     * {@link Thread#start()}, so a factory whose {@code start} throws must not leave that assignment stuck.
     */
    static volatile ThreadFactory loaderThreads = DEFAULT_LOADER_THREADS;

    private static final String SAVE_PAUSED = "Saves of dialogue-memory.yml are paused because the first load "
            + "did not finish. Restart the server to load the file and resume saves.";

    private final MemoryStore memory;
    private final Supplier<File> file;
    private final Supplier<DialogueSettings> settings;
    private final Supplier<List<String>> secrets;
    private final Logger logger;
    private final LongSupplier clock;

    private volatile boolean memoryLoadedFromDisk;
    private Thread diskLoader;
    private MemoryStore.AbsentScan absentScan;
    private volatile long loadStartedAtMillis = Long.MIN_VALUE;
    private volatile boolean loadFinished;
    private boolean savePauseLogged;

    DialogueMemoryPersistence(
            MemoryStore memory,
            Supplier<File> file,
            Supplier<DialogueSettings> settings,
            Supplier<List<String>> secrets,
            Logger logger,
            LongSupplier clock
    ) {
        this.memory = memory;
        this.file = file;
        this.settings = settings;
        this.secrets = secrets;
        this.logger = logger;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Startup already loaded the file on this thread. Later reloads must not read it again. */
    void loadedAtStartup() {
        memoryLoadedFromDisk = true;
    }

    boolean memoryLoadedFromDisk() {
        return memoryLoadedFromDisk;
    }

    Thread diskLoader() {
        synchronized (this) {
            return diskLoader;
        }
    }

    boolean loadFinished() {
        return loadFinished;
    }

    /**
     * When persistence flips from off to on, start one background read. A later reload does not
     * start another read while that one is in progress or after it has succeeded.
     */
    void onReload() {
        synchronized (this) {
            if (memoryLoadedFromDisk || diskLoader != null) {
                return;
            }
            DialogueSettings current = settings.get();
            if (current == null || !current.persistMemory()) {
                return;
            }
            long now = clock.getAsLong();
            long expiry = current.memoryExpiryMillis();
            List<String> secretValues = secretList();
            File target = file.get();
            Runnable task = () -> {
                try {
                    if (memory.loadForPersistence(target, now, expiry, logger, secretValues)) {
                        memoryLoadedFromDisk = true;
                    }
                } catch (Throwable error) {
                    DialogueService.logMemoryLoadFailure(logger, error, secretValues);
                } finally {
                    synchronized (DialogueMemoryPersistence.this) {
                        loadFinished = true;
                        diskLoader = null;
                    }
                }
            };
            Thread worker = loaderThreads.newThread(task);
            if (worker == null) {
                return;
            }
            loadFinished = false;
            loadStartedAtMillis = now;
            savePauseLogged = false;
            // Assigned before start so a second reload sees the in-flight read. start() failing
            // does not run the task, so the task's finally cannot clear this. Drop it here.
            diskLoader = worker;
            // Keys are read while the load runs, and stop joins that load for one second.
            absentScan = MemoryStore.startAbsentScan(target);
            try {
                worker.start();
            } catch (Throwable startFailed) {
                diskLoader = null;
                absentScan = null;
                loadFinished = true;
                DialogueService.logMemoryLoadFailure(logger, startFailed, secretValues);
            }
        }
    }

    /** Periodic save. Skipped, with one warning, when the first off-to-on load failed or hung. */
    void save() {
        try {
            writeSave();
        } catch (Throwable thrown) {
            logSkipped(thrown);
        }
    }

    /**
     * Stop. A load that is still running after {@link #shutdownLoadGraceMillis} does not block disable
     * for the rest of the read. Disk characters are kept. Characters that were never in the file are
     * appended. A failed load does not replace the file.
     */
    void shutdown() {
        try {
            Thread pending;
            MemoryStore.AbsentScan scan;
            synchronized (this) {
                pending = diskLoader;
                scan = absentScan;
            }
            if (pending != null && pending.isAlive() && scan == null) {
                scan = MemoryStore.startAbsentScan(file.get());
            }
            if (pending != null) {
                try {
                    pending.join(Math.max(1L, shutdownLoadGraceMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (memoryLoadedFromDisk) {
                writeSave();
                return;
            }
            synchronized (this) {
                pending = diskLoader;
            }
            if (pending != null && pending.isAlive()) {
                DialogueSettings current = settings.get();
                boolean summaries = current != null && current.summaryEnabled();
                boolean wrote = memory.appendCharactersAbsentFromFile(
                        file.get(), logger, summaries, secretList(), scan);
                if (!wrote) {
                    noteSkippedSave();
                }
                return;
            }
            writeSave();
        } catch (Throwable thrown) {
            logSkipped(thrown);
        }
    }

    private void writeSave() {
        DialogueSettings current = settings.get();
        if (current == null || !current.persistMemory() || !memoryLoadedFromDisk) {
            noteSkippedSave();
            return;
        }
        memory.save(file.get(), logger, current.summaryEnabled());
    }

    /**
     * One warning when saves are skipped because the first load failed, or because it is still
     * running after {@link #LOAD_HANG_WARNING_AFTER_MILLIS}. The opening of a live load stays quiet.
     */
    private void noteSkippedSave() {
        if (memoryLoadedFromDisk || loadStartedAtMillis == Long.MIN_VALUE) {
            return;
        }
        DialogueSettings current = settings.get();
        if (current == null || !current.persistMemory()) {
            return;
        }
        boolean attemptOver = loadFinished;
        boolean hung = !attemptOver && clock.getAsLong() - loadStartedAtMillis >= LOAD_HANG_WARNING_AFTER_MILLIS;
        if (!attemptOver && !hung) {
            return;
        }
        synchronized (this) {
            if (memoryLoadedFromDisk || savePauseLogged) {
                return;
            }
            if (!loadFinished && clock.getAsLong() - loadStartedAtMillis < LOAD_HANG_WARNING_AFTER_MILLIS) {
                return;
            }
            savePauseLogged = true;
        }
        if (logger != null) {
            logger.warning(SAVE_PAUSED);
        }
    }

    private List<String> secretList() {
        List<String> values = secrets.get();
        return values == null ? List.of() : List.copyOf(values);
    }

    private void logSkipped(Throwable thrown) {
        if (logger == null) {
            return;
        }
        logger.fine("Skipped dialogue memory save: "
                + SecretMask.redact(String.valueOf(thrown.getMessage()), secretList()));
    }
}
