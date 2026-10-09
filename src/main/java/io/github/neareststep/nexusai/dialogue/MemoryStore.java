package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.FileBackup;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.config.SecretMask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * In-memory transcripts with an optional {@code dialogue-memory.yml} snapshot.
 */
public final class MemoryStore {

    /** A temp sibling left behind when the process died between create and rename. */
    private static final Pattern STALE_TEMP = Pattern.compile(
            "^dialogue-memory\\.yml\\.[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp$");

    private final ConcurrentHashMap<String, TurnMemory> memories = new ConcurrentHashMap<>();
    /** Set when a broken file could not be moved aside, so a later save must not replace it. */
    private boolean saveBlocked;
    private boolean saveBlockedLogged;
    /** Load and save take this in turn, so a save cannot snapshot a store that is still loading. */
    final ReentrantLock diskLock = new ReentrantLock();
    /** Resolved API keys. A data-directory path in a save warning is masked with these. */
    private volatile Supplier<Iterable<String>> secretSource = List::of;
    /** Runs with {@link #diskLock} held, before a load changes the store. Tests pause a load here. */
    static Runnable pauseDuringLoad;

    /** Runs on the key-scan thread before it reads the file. Tests use this to pause or fail that scan. */
    static volatile Runnable beforeKeyScan;

    /**
     * Runs after a stop write has filled its temporary file and before that file is renamed.
     * Tests use this to cross the stop budget. A null hook is the production path.
     */
    static volatile Runnable beforeReplace;

    /** When set, the next stop rename fails and the temporary file is removed. */
    static volatile boolean failNextPublish;

    /**
     * Set when stop gives up on a load that is still reading. The load returns without
     * replacing the file and without logging a second failure.
     */
    static volatile boolean abandonActiveLoad;

    /**
     * Times {@link #appendWithFullDocument} ran. A stop that splices, or that refuses the file,
     * leaves this unchanged. Tests reset it.
     */
    static final AtomicInteger fullDocumentAppends = new AtomicInteger();

    /**
     * Times the event scan of player and character keys ran. Tests reset it.
     */
    static final AtomicInteger eventScans = new AtomicInteger();

    /**
     * Replaces the atomic rename of a quarantine copy. Tests use this to stop after the temp file
     * is complete and before {@code .corrupt} exists.
     */
    static CorruptMove corruptMove = MemoryStore::renameCorrupt;

    /**
     * Fsync used before and after an autosave rename. Tests substitute this to record the order
     * and to fail the sync. A failure must not abort the save.
     */
    static DiskSync diskSync = MemoryStore::forceFile;

    /** When non-null, autosave records {@code force-temp}, {@code rename}, {@code force-dir}. */
    static List<String> durableTrace;

    /** Held around a load's file mutation and around a shutdown merge, so the two cannot overlap. */
    final Object publishGate = new Object();
    /**
     * Set when shutdown has already published a merged file. The in-flight load must not rewrite
     * or quarantine over that file.
     */
    volatile boolean loaderMustNotPublish;

    @FunctionalInterface
    interface CorruptMove {
        void move(Path temporary, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface DiskSync {
        void force(Path path) throws IOException;
    }

    public TurnMemory get(UUID player, String characterId) {
        return memories.computeIfAbsent(key(player, characterId), ignored -> new TurnMemory());
    }

    public synchronized void append(
            UUID player,
            String characterId,
            String role,
            String text,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis
    ) {
        append(player, characterId, role, text, nowMillis, turns, maxChars, expiryMillis, false);
    }

    public synchronized void append(
            UUID player,
            String characterId,
            String role,
            String text,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis,
            boolean fold
    ) {
        TurnMemory memory = get(player, characterId);
        memory.expire(nowMillis, expiryMillis);
        memory.add(role, text, nowMillis);
        memory.trim(turns, maxChars, fold);
    }

    public List<TurnMemory.Line> transcript(
            UUID player,
            String characterId,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis
    ) {
        return transcript(player, characterId, nowMillis, turns, maxChars, expiryMillis, false);
    }

    public List<TurnMemory.Line> transcript(
            UUID player,
            String characterId,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis,
            boolean fold
    ) {
        TurnMemory memory = get(player, characterId);
        memory.expire(nowMillis, expiryMillis);
        memory.trim(turns, maxChars, fold);
        return memory.view();
    }

    /**
     * The previous player line. Expires a stale transcript and does not trim it.
     */
    public String lastUserLine(UUID player, String characterId, long nowMillis, long expiryMillis) {
        TurnMemory stored = get(player, characterId);
        stored.expire(nowMillis, expiryMillis);
        return stored.lastUserText();
    }

    public String summary(UUID player, String characterId, long nowMillis, long expiryMillis) {
        TurnMemory memory = get(player, characterId);
        memory.expire(nowMillis, expiryMillis);
        return memory.summary();
    }

    public TurnMemory.Fold claimSummary(UUID player, String characterId, int thresholdTurns) {
        return get(player, characterId).claim(thresholdTurns);
    }

    public boolean completeSummary(UUID player, String characterId, String text, long nowMillis, int epoch) {
        return get(player, characterId).completeSummary(text, nowMillis, epoch);
    }

    public void failSummary(UUID player, String characterId, int epoch) {
        get(player, characterId).failSummary(epoch);
    }

    public void load(File file, long nowMillis, long expiryMillis, Logger logger) {
        load(file, nowMillis, expiryMillis, logger, List.of());
    }

    /** Keys used to mask a path in a dialogue-memory.yml warning. Empty until the plugin wires config. */
    public void secrets(Supplier<Iterable<String>> secrets) {
        this.secretSource = secrets == null ? List::of : secrets;
    }

    private Iterable<String> secrets() {
        Iterable<String> values = secretSource.get();
        return values == null ? List.of() : values;
    }

    /**
     * Loads transcripts and, when a summary or line still holds a configured key or a vendor-shaped
     * key, writes that text back masked. Entries that are not loaded (they are expired, or persistence
     * is off) are masked in the file as well. A file with nothing to mask is left byte for byte.
     */
    public void load(File file, long nowMillis, long expiryMillis, Logger logger, Iterable<String> secrets) {
        diskLock.lock();
        try {
            loadHoldingLock(file, nowMillis, expiryMillis, logger, secrets);
        } finally {
            diskLock.unlock();
        }
    }

    private void loadHoldingLock(File file, long nowMillis, long expiryMillis, Logger logger, Iterable<String> secrets) {
        Runnable pause = pauseDuringLoad;
        if (pause != null) {
            pause.run();
        }
        if (abandonActiveLoad) {
            return;
        }
        YamlConfiguration yaml = readRedacting(file, secrets, logger, this);
        if (yaml == null) {
            return;
        }
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) {
            return;
        }
        for (String playerKey : entries.getKeys(false)) {
            UUID player;
            try {
                player = UUID.fromString(playerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection characters = entries.getConfigurationSection(playerKey);
            if (characters == null) {
                continue;
            }
            for (String characterId : characters.getKeys(false)) {
                ConfigurationSection one = characters.getConfigurationSection(characterId);
                if (one == null) {
                    continue;
                }
                long updated = one.getLong("updated", 0L);
                if (expiryMillis > 0 && updated > 0 && nowMillis - updated >= expiryMillis) {
                    continue;
                }
                List<TurnMemory.Line> lines = new ArrayList<>();
                for (Object raw : one.getMapList("lines")) {
                    if (!(raw instanceof java.util.Map<?, ?> map)) {
                        continue;
                    }
                    Object role = map.get("role");
                    Object text = map.get("text");
                    if (role == null || text == null) {
                        continue;
                    }
                    String roleText = String.valueOf(role);
                    if (!"user".equals(roleText) && !"assistant".equals(roleText)) {
                        continue;
                    }
                    lines.add(new TurnMemory.Line(roleText, String.valueOf(text)));
                }
                String summary = one.getString("summary", "");
                long summaryUpdated = summary == null || summary.isBlank() ? 0L : one.getLong("summary-updated", 0L);
                get(player, characterId).load(lines, updated, summary, summaryUpdated);
            }
        }
    }

    /**
     * Masks keys already stored in {@code dialogue-memory.yml} without loading them into this store.
     * Used when persistence is off, so a key written by an older version does not stay on disk.
     * When {@code store} is the live store and the quarantine copy cannot be written, later saves on
     * that store refuse to replace the file.
     */
    public static void redactOnDisk(File file, Iterable<String> secrets, Logger logger, MemoryStore store) {
        readRedacting(file, secrets, logger, store);
    }

    /**
     * Masks keys already stored in {@code dialogue-memory.yml} without loading them into a store.
     */
    public static void redactOnDisk(File file, Iterable<String> secrets, Logger logger) {
        redactOnDisk(file, secrets, logger, null);
    }

    /**
     * Loads a file that stayed on disk while persistence was off.
     * On-disk lines replace the in-memory transcript for each character in the file, so a valid file
     * is not later saved over by an empty store. A store that already refused to replace an unreadable
     * file is left unchanged.
     *
     * @return {@code false} when this store must not replace the file
     */
    public boolean loadForPersistence(File file, long nowMillis, long expiryMillis, Logger logger, Iterable<String> secrets) {
        diskLock.lock();
        try {
            if (saveBlocked || abandonActiveLoad) {
                return false;
            }
            loadHoldingLock(file, nowMillis, expiryMillis, logger, secrets);
            if (abandonActiveLoad) {
                return false;
            }
            return !saveBlocked;
        } finally {
            diskLock.unlock();
        }
    }

    private static YamlConfiguration readRedacting(File file, Iterable<String> secrets, Logger logger, MemoryStore store) {
        sweepStaleTemps(file, logger);
        if (file == null || !file.isFile()) {
            return null;
        }
        if (abandonActiveLoad) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = new AbandonableInputStream(new FileInputStream(file))) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (LoadAbandoned ignored) {
            return null;
        } catch (Exception e) {
            boolean preserved = quarantineUnreadable(file, secrets, logger, e, store);
            if (store != null && !preserved) {
                store.saveBlocked = true;
            }
            return null;
        }
        if (redactDocument(yaml, secrets) && rewrite(file, yaml, logger, secrets, store) && logger != null) {
            logger.info("Masked API keys in dialogue-memory.yml");
        }
        return yaml;
    }

    private static boolean redactDocument(YamlConfiguration yaml, Iterable<String> secrets) {
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) {
            return false;
        }
        boolean dirty = false;
        for (String playerKey : entries.getKeys(false)) {
            ConfigurationSection characters = entries.getConfigurationSection(playerKey);
            if (characters == null) {
                continue;
            }
            for (String characterId : characters.getKeys(false)) {
                ConfigurationSection one = characters.getConfigurationSection(characterId);
                if (one == null) {
                    continue;
                }
                boolean summaryDirty = redactSummary(one, secrets);
                boolean linesDirty = redactLines(one, secrets);
                if (summaryDirty || linesDirty) {
                    dirty = true;
                }
            }
        }
        return dirty;
    }

    private static boolean redactSummary(ConfigurationSection one, Iterable<String> secrets) {
        if (!one.contains("summary")) {
            return false;
        }
        String summary = one.getString("summary");
        if (summary == null) {
            return false;
        }
        String masked = SecretMask.redact(summary, secrets);
        if (masked.equals(summary)) {
            return false;
        }
        one.set("summary", masked);
        return true;
    }

    private static boolean redactLines(ConfigurationSection one, Iterable<String> secrets) {
        if (!one.contains("lines")) {
            return false;
        }
        List<Map<?, ?>> rows = one.getMapList("lines");
        if (rows.isEmpty()) {
            return false;
        }
        boolean dirty = false;
        List<Map<String, Object>> stored = new ArrayList<>();
        for (Map<?, ?> map : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            Object text = copy.get("text");
            if (text != null) {
                String original = String.valueOf(text);
                String masked = SecretMask.redact(original, secrets);
                if (!masked.equals(original)) {
                    copy.put("text", masked);
                    dirty = true;
                }
            }
            stored.add(copy);
        }
        if (dirty) {
            one.set("lines", stored);
        }
        return dirty;
    }

    private static boolean rewrite(File file, YamlConfiguration yaml, Logger logger, Iterable<String> secrets, MemoryStore store) {
        if (store == null) {
            return rewriteUnlocked(file, yaml, logger, secrets);
        }
        synchronized (store.publishGate) {
            if (store.loaderMustNotPublish) {
                return false;
            }
            return rewriteUnlocked(file, yaml, logger, secrets);
        }
    }

    private static boolean rewriteUnlocked(File file, YamlConfiguration yaml, Logger logger, Iterable<String> secrets) {
        File parent = file.getParentFile();
        File temporary = new File(parent == null ? new File(".") : parent,
                file.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }
            AtomicFiles.createPrivate(temporary.toPath());
            allowLongSimpleKeys(yaml);
            yaml.save(temporary);
            durableReplace(temporary.toPath(), file.toPath());
            // The file just held a key. Do not keep a group- or world-readable mode.
            AtomicFiles.restrictOwnerReadWrite(file.toPath());
            return true;
        } catch (IOException e) {
            if (logger != null) {
                LogRedaction.warning(logger, "Failed to save dialogue-memory.yml", e, secrets);
            }
            return false;
        } finally {
            if (temporary.isFile() && !temporary.equals(file)) {
                temporary.delete();
            }
        }
    }

    public void save(File file, Logger logger) {
        save(file, logger, false);
    }

    /**
     * @param summaries when false, the file keeps the 1.0.2 keys only ({@code updated} and {@code lines}).
     *                  When true, the document is {@code format: 2} and includes {@code summary}.
     *                  The fold buffer is never written. A file that is not yet format 2 is copied to
     *                  {@code .bak} once before the first format-2 replace.
     */
    public void save(File file, Logger logger, boolean summaries) {
        save(file, logger, summaries, MemoryStore::moveIntoPlace);
    }

    void save(File file, Logger logger, boolean summaries, Publish publish) {
        if (file == null) {
            return;
        }
        diskLock.lock();
        try {
            saveHoldingLock(file, logger, summaries, publish, Long.MAX_VALUE);
        } finally {
            diskLock.unlock();
        }
    }

    /**
     * The save stop uses after a live load has finished. A deadline that has already passed does
     * not create a temporary file. A write that crosses the deadline does not rename.
     */
    StopSave saveForStop(File file, Logger logger, boolean summaries, long deadlineNanos) {
        if (file == null) {
            return StopSave.SKIPPED;
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return StopSave.PAST_DEADLINE;
        }
        diskLock.lock();
        try {
            if (saveBlocked) {
                noteSaveBlocked(logger);
                return StopSave.NOT_WRITTEN;
            }
            WriteResult wrote = publishYaml(
                    file, document(summaries), logger, summaries, MemoryStore::moveIntoPlace, deadlineNanos);
            return switch (wrote) {
                case PUBLISHED -> StopSave.SAVED;
                case PAST_DEADLINE -> StopSave.PAST_DEADLINE;
                case FAILED -> StopSave.NOT_WRITTEN;
            };
        } finally {
            diskLock.unlock();
        }
    }

    private void saveHoldingLock(File file, Logger logger, boolean summaries, Publish publish, long deadlineNanos) {
        if (saveBlocked) {
            noteSaveBlocked(logger);
            return;
        }
        publishYaml(file, document(summaries), logger, summaries, publish, deadlineNanos);
    }

    private void noteSaveBlocked(Logger logger) {
        if (logger != null && !saveBlockedLogged) {
            saveBlockedLogged = true;
            logger.warning("Refusing to overwrite dialogue-memory.yml because it could not be read. "
                    + "Repair the broken file and restart.");
        }
    }

    /**
     * Writes {@code yaml} via a temp file and {@code publish}. A backup failure is logged and does
     * not also log a save failure. The temporary file is removed when the rename does not happen.
     */
    private WriteResult publishYaml(
            File file,
            YamlConfiguration yaml,
            Logger logger,
            boolean summaries,
            Publish publish,
            long deadlineNanos
    ) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return WriteResult.PAST_DEADLINE;
        }
        File parent = file.getParentFile();
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return WriteResult.FAILED;
            }
            if (summaries && file.isFile() && !hasFormat2(file)) {
                try {
                    java.nio.file.Path backup = FileBackup.backup(file.toPath());
                    if (logger != null) {
                        logger.info("Backed up dialogue-memory.yml to " + backup.getFileName());
                    }
                } catch (IOException e) {
                    if (logger != null) {
                        LogRedaction.warning(logger, "Failed to back up dialogue-memory.yml", e, secrets());
                    }
                    return WriteResult.FAILED;
                }
            }
            File temporary = new File(parent == null ? new File(".") : parent,
                    file.getName() + "." + UUID.randomUUID() + ".tmp");
            try {
            AtomicFiles.createPrivate(temporary.toPath());
            if (containsForcedQuote(yaml)) {
                Files.writeString(temporary.toPath(), dumpDocument(configurationMap(yaml)), StandardCharsets.UTF_8);
            } else {
                allowLongSimpleKeys(yaml);
                yaml.save(temporary);
            }
            WriteResult gate = gateReplace(deadlineNanos);
            if (gate != WriteResult.PUBLISHED) {
                return gate;
            }
            publish.publish(temporary, file);
            } finally {
                if (temporary.isFile() && !temporary.equals(file)) {
                    temporary.delete();
                }
            }
            return WriteResult.PUBLISHED;
        } catch (IOException e) {
            if (logger != null) {
                LogRedaction.warning(logger, "Failed to save dialogue-memory.yml", e, secrets());
            }
            return WriteResult.FAILED;
        }
    }

    /**
     * Decides whether a temporary file may replace the live file. A budget that has already ended
     * leaves the live file untouched. {@link #failNextPublish} forces the write to fail.
     */
    private static WriteResult gateReplace(long deadlineNanos) throws IOException {
        Runnable hook = beforeReplace;
        if (hook != null && deadlineNanos != Long.MAX_VALUE) {
            hook.run();
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return WriteResult.PAST_DEADLINE;
        }
        if (failNextPublish) {
            failNextPublish = false;
            throw new IOException("dialogue-memory.yml could not be written");
        }
        return WriteResult.PUBLISHED;
    }

    /**
     * Adds transcripts that are not already in {@code file}, leaving every on-disk character as it
     * was. An unreadable file is left untouched. Does not take {@link #diskLock}: a load may hold
     * that lock for the whole read, and shutdown has to finish without waiting for it.
     * <p>
     * When {@code prepared} is set, it is the scan stop started. Stop waits for it only until
     * {@code deadlineNanos}. A scan that cannot splice a file does not read that file again when
     * a load of it is already running; the caller waits for that load instead. A direct call, with
     * no live load, may still rewrite a readable file the scan cannot splice. A scan that is still
     * running, that crashed, or that cannot read the file does not publish, and one warning names
     * how many characters were not saved and why. The deadline also covers the write: a rename is
     * not started after it has passed.
     *
     * @return {@code true} when a merged file was published
     */
    boolean appendCharactersAbsentFromFile(File file, Logger logger, boolean summaries, Iterable<String> secrets) {
        return appendCharactersAbsentFromFile(file, logger, summaries, secrets, null, Long.MAX_VALUE);
    }

    boolean appendCharactersAbsentFromFile(
            File file, Logger logger, boolean summaries, Iterable<String> secrets, AbsentScan prepared) {
        return appendCharactersAbsentFromFile(file, logger, summaries, secrets, prepared, Long.MAX_VALUE);
    }

    boolean appendCharactersAbsentFromFile(
            File file,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            AbsentScan prepared,
            long deadlineNanos
    ) {
        if (saveBlocked || file == null || !file.isFile()) {
            return false;
        }
        if (prepared != null) {
            PreparedKind kind = takePrepared(file, logger, summaries, secrets, prepared, deadlineNanos);
            if (kind == PreparedKind.REFUSED) {
                return finishWithFullDocument(file, logger, summaries, secrets, deadlineNanos);
            }
            warnFor(kind, logger, summaries);
            return kind == PreparedKind.PUBLISHED;
        }
        Snapshot snap;
        try {
            snap = readSnapshot(file, null);
        } catch (IOException e) {
            warnUnsaved(logger, unsavedCharacterCount(summaries), SkipReason.UNREADABLE);
            return false;
        }
        if (snap.unreadable) {
            warnUnsaved(logger, unsavedCharacterCount(summaries), SkipReason.UNREADABLE);
            return false;
        }
        if (snap.text != null && snap.outline != null) {
            SpliceResult spliced = splice(file, snap.text, snap.outline, logger, summaries, secrets, Long.MAX_VALUE);
            if (spliced == SpliceResult.PUBLISHED) {
                return true;
            }
            if (spliced == SpliceResult.NOTHING) {
                return false;
            }
            if (spliced == SpliceResult.PAST_DEADLINE) {
                warnUnsaved(logger, unsavedCharacterCount(summaries), SkipReason.BUDGET);
                return false;
            }
        }
        FullAppend outcome = appendWithFullDocument(file, logger, summaries, secrets, Long.MAX_VALUE);
        if (outcome == FullAppend.PUBLISHED) {
            return true;
        }
        if (outcome == FullAppend.NOTHING) {
            return false;
        }
        warnUnsaved(logger, unsavedCharacterCount(summaries), reasonFor(outcome));
        return false;
    }

    /**
     * Splice from the scan stop started. Never reads the file a second time. {@link PreparedKind#REFUSED}
     * means the caller should wait for a load that is already running, or, when there is no such
     * load, rewrite the document.
     */
    LiveSplice spliceForStop(
            File file,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            AbsentScan prepared,
            long deadlineNanos
    ) {
        if (prepared == null) {
            return LiveSplice.WAIT_FOR_LOAD;
        }
        PreparedKind kind = takePrepared(file, logger, summaries, secrets, prepared, deadlineNanos);
        if (kind == PreparedKind.REFUSED) {
            return LiveSplice.WAIT_FOR_LOAD;
        }
        warnFor(kind, logger, summaries);
        return LiveSplice.DONE;
    }

    private PreparedKind takePrepared(
            File file,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            AbsentScan prepared,
            long deadlineNanos
    ) {
        Snapshot snap = prepared.awaitSnapshot(deadlineNanos);
        if (prepared.crashed()) {
            prepared.releaseAll();
            return PreparedKind.SCAN_FAILED;
        }
        if (snap == null) {
            prepared.releaseAll();
            return PreparedKind.BUDGET;
        }
        if (snap.unreadable) {
            prepared.releaseAll();
            return PreparedKind.UNREADABLE;
        }
        boolean canSplice = false;
        try {
            canSplice = snap.text != null && snap.outline != null && bytesMatch(file, snap);
        } catch (IOException e) {
            prepared.releaseAll();
            return PreparedKind.UNREADABLE;
        }
        String text = canSplice ? snap.text : null;
        StreamOutline outline = canSplice ? snap.outline : null;
        prepared.releaseAll();
        if (!canSplice) {
            return PreparedKind.REFUSED;
        }
        SpliceResult spliced = splice(file, text, outline, logger, summaries, secrets, deadlineNanos);
        return switch (spliced) {
            case PUBLISHED -> PreparedKind.PUBLISHED;
            case NOTHING -> PreparedKind.NOTHING;
            case PAST_DEADLINE -> PreparedKind.BUDGET;
            case FAILED -> PreparedKind.REFUSED;
        };
    }

    private void warnFor(PreparedKind kind, Logger logger, boolean summaries) {
        SkipReason reason = switch (kind) {
            case BUDGET -> SkipReason.BUDGET;
            case SCAN_FAILED -> SkipReason.SCAN_FAILED;
            case UNREADABLE -> SkipReason.UNREADABLE;
            case UNWRITTEN -> SkipReason.UNWRITTEN;
            case PUBLISHED, NOTHING, REFUSED -> null;
        };
        if (reason != null) {
            warnUnsaved(logger, unsavedCharacterCount(summaries), reason);
        }
    }

    void warnStop(Logger logger, boolean summaries, StopSkip skip) {
        SkipReason reason = switch (skip) {
            case BUDGET -> SkipReason.BUDGET;
            case SCAN_FAILED -> SkipReason.SCAN_FAILED;
            case UNREADABLE -> SkipReason.UNREADABLE;
            case UNWRITTEN -> SkipReason.UNWRITTEN;
        };
        warnUnsaved(logger, unsavedCharacterCount(summaries), reason);
    }

    /**
     * The event scan refused a file, or could not splice one character. Rewrite the document when
     * a normal load can read it and the rewrite finishes before {@code deadlineNanos}.
     */
    private boolean finishWithFullDocument(
            File file,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            long deadlineNanos
    ) {
        FullAppend outcome = appendWithFullDocument(file, logger, summaries, secrets, deadlineNanos);
        if (outcome == FullAppend.PUBLISHED) {
            return true;
        }
        if (outcome == FullAppend.NOTHING) {
            return false;
        }
        warnUnsaved(logger, unsavedCharacterCount(summaries), reasonFor(outcome));
        return false;
    }

    private static SkipReason reasonFor(FullAppend outcome) {
        return switch (outcome) {
            case NOT_FINISHED -> SkipReason.BUDGET;
            case UNWRITTEN -> SkipReason.UNWRITTEN;
            default -> SkipReason.UNREADABLE;
        };
    }

    private SpliceResult splice(
            File file,
            String text,
            StreamOutline outline,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            long deadlineNanos
    ) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return SpliceResult.PAST_DEADLINE;
        }
        Map<String, StoredTranscript> extra;
        synchronized (this) {
            extra = transcriptsAbsent(outline.characterKeys, summaries);
        }
        if (extra.isEmpty()) {
            return SpliceResult.NOTHING;
        }
        List<TextEdit> edits = editsAbsent(text, outline, redactExtra(extra, secrets), summaries);
        if (edits == null) {
            return SpliceResult.FAILED;
        }
        WriteResult wrote = publishEdits(file, text, edits, logger, summaries, outline.format2, secrets, deadlineNanos);
        return switch (wrote) {
            case PUBLISHED -> SpliceResult.PUBLISHED;
            case PAST_DEADLINE -> SpliceResult.PAST_DEADLINE;
            case FAILED -> SpliceResult.FAILED;
        };
    }

    private enum SpliceResult {
        NOTHING, PUBLISHED, FAILED, PAST_DEADLINE
    }

    /**
     * Reads player and character keys on a daemon thread. Stop starts this when stop begins,
     * so the read overlaps the one-second join.
     */
    static AbsentScan startAbsentScan(File file) {
        return AbsentScan.start(file);
    }

    /** Stops a key scan that is still reading. A scan that has already finished is left alone. */
    static void abandonScan(AbsentScan scan) {
        if (scan != null) {
            scan.abandonAndJoin();
        }
    }

    private WriteResult publishEdits(
            File file,
            String text,
            List<TextEdit> edits,
            Logger logger,
            boolean summaries,
            boolean format2,
            Iterable<String> secrets,
            long deadlineNanos
    ) {
        synchronized (publishGate) {
            if (saveBlocked || !file.isFile()) {
                return WriteResult.FAILED;
            }
            if (loaderMustNotPublish) {
                return WriteResult.PUBLISHED;
            }
            loaderMustNotPublish = true;
            WriteResult published = writeAppended(
                    file, text, edits, logger, summaries, format2, secrets, deadlineNanos);
            if (published != WriteResult.PUBLISHED) {
                loaderMustNotPublish = false;
            }
            return published;
        }
    }

    /**
     * The 1.1.2 merge: load the document, add characters that are absent, and write it back.
     * Used only when no load of this file is already running. The load runs on the caller.
     * A deadline that has already passed does not start the load or the rename.
     */
    private FullAppend appendWithFullDocument(
            File file, Logger logger, boolean summaries, Iterable<String> secrets, long deadlineNanos) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return FullAppend.NOT_FINISHED;
        }
        fullDocumentAppends.incrementAndGet();
        YamlConfiguration yaml = loadDocument(file);
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return FullAppend.NOT_FINISHED;
        }
        return mergeLoaded(file, yaml, logger, summaries, secrets, deadlineNanos);
    }

    private static YamlConfiguration loadDocument(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return null;
        }
        return yaml;
    }

    private FullAppend mergeLoaded(
            File file,
            YamlConfiguration yaml,
            Logger logger,
            boolean summaries,
            Iterable<String> secrets,
            long deadlineNanos
    ) {
        if (yaml == null) {
            return FullAppend.UNREADABLE;
        }
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return FullAppend.NOT_FINISHED;
        }
        Set<String> present = characterKeys(yaml);
        Map<String, StoredTranscript> extra;
        synchronized (this) {
            extra = transcriptsAbsent(present, summaries);
        }
        if (extra.isEmpty()) {
            return FullAppend.NOTHING;
        }
        if (summaries) {
            yaml.set("format", 2);
        }
        for (Map.Entry<String, StoredTranscript> entry : redactExtra(extra, secrets).entrySet()) {
            writeTranscript(yaml, entry.getKey(), entry.getValue(), summaries);
        }
        return switch (publishYamlLocked(file, yaml, logger, summaries, deadlineNanos)) {
            case PUBLISHED -> FullAppend.PUBLISHED;
            case PAST_DEADLINE -> FullAppend.NOT_FINISHED;
            case FAILED -> FullAppend.UNWRITTEN;
        };
    }

    private enum FullAppend {
        PUBLISHED, NOTHING, UNREADABLE, UNWRITTEN, NOT_FINISHED
    }

    enum StopSave {
        SAVED, PAST_DEADLINE, NOT_WRITTEN, SKIPPED
    }

    enum StopSkip {
        BUDGET, SCAN_FAILED, UNREADABLE, UNWRITTEN
    }

    enum LiveSplice {
        DONE, WAIT_FOR_LOAD
    }

    private enum PreparedKind {
        PUBLISHED, NOTHING, REFUSED, BUDGET, SCAN_FAILED, UNREADABLE, UNWRITTEN
    }

    private enum WriteResult {
        PUBLISHED, FAILED, PAST_DEADLINE
    }

    private enum SkipReason {
        BUDGET("it did not finish within the stop budget"),
        SCAN_FAILED("the key scan failed"),
        UNREADABLE("dialogue-memory.yml could not be read"),
        UNWRITTEN("dialogue-memory.yml could not be written");

        private final String text;

        SkipReason(String text) {
            this.text = text;
        }
    }

    private WriteResult publishYamlLocked(
            File file, YamlConfiguration yaml, Logger logger, boolean summaries, long deadlineNanos) {
        synchronized (publishGate) {
            if (saveBlocked || !file.isFile()) {
                return WriteResult.FAILED;
            }
            if (loaderMustNotPublish) {
                return WriteResult.PUBLISHED;
            }
            loaderMustNotPublish = true;
            WriteResult published = publishYaml(file, yaml, logger, summaries, MemoryStore::moveIntoPlace, deadlineNanos);
            if (published != WriteResult.PUBLISHED) {
                loaderMustNotPublish = false;
            }
            return published;
        }
    }

    /**
     * Character ids already in the file, from SnakeYAML events. Folded and block scalars stay one
     * event, so a continuation line does not hide a character. Null when the shape is not one this
     * append can splice without rewriting existing text.
     */
    private static EventRead parseEvents(String text, AbsentScan scan) throws IOException {
        eventScans.incrementAndGet();
        // These breaks are line breaks to the parser and are not stable offsets in the original text.
        if (text.indexOf('\u0085') >= 0 || text.indexOf('\u2028') >= 0 || text.indexOf('\u2029') >= 0) {
            return EventRead.refused();
        }
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setProcessComments(true);
        Yaml yaml = new Yaml(options);
        StreamOutline outline = new StreamOutline();
        java.util.ArrayDeque<ScanFrame> stack = new java.util.ArrayDeque<>();
        MarkIndex marks = new MarkIndex();
        try {
            for (Event event : yaml.parse(new StringReader(text))) {
                if (scan != null && scan.abandoned) {
                    throw new LoadAbandoned();
                }
                switch (event.getEventId()) {
                    case StreamStart, StreamEnd, DocumentStart, DocumentEnd, Comment -> {
                    }
                    case Alias -> throw new ScanFallback();
                    case MappingStart -> onMappingStart(outline, stack, (MappingStartEvent) event);
                    case MappingEnd -> onMappingEnd(outline, stack, event);
                    case SequenceStart -> onSequenceStart(outline, stack);
                    case SequenceEnd -> {
                        if (stack.isEmpty() || stack.peek().kind != ScanKind.SEQUENCE) {
                            throw new ScanFallback();
                        }
                        stack.pop();
                    }
                    case Scalar -> onScalar(outline, stack, (ScalarEvent) event, text, marks);
                    default -> throw new ScanFallback();
                }
            }
        } catch (ScanFallback e) {
            return EventRead.refused();
        } catch (RuntimeException e) {
            return EventRead.unreadable();
        }
        if (!stack.isEmpty()) {
            return EventRead.refused();
        }
        return EventRead.ok(outline);
    }

    /**
     * Character keys in {@code text}, or null when the event scan cannot read the file or cannot
     * splice it. Each key is {@code playerId + NUL + characterId}, the same pair
     * {@link YamlConfiguration} reads. An empty or null character value is omitted, because a
     * later load drops it.
     */
    static Set<String> scanCharacterKeys(String text) {
        EventRead read;
        try {
            read = parseEvents(text, null);
        } catch (IOException e) {
            return null;
        }
        if (read.outline == null) {
            return null;
        }
        return Set.copyOf(read.outline.characterKeys);
    }

    private static void onMappingStart(StreamOutline outline, java.util.ArrayDeque<ScanFrame> stack, MappingStartEvent event) {
        ScanFrame parent = stack.peek();
        if (parent == null) {
            stack.push(new ScanFrame(ScanKind.ROOT));
            return;
        }
        if (event.isFlow()) {
            throw new ScanFallback();
        }
        if (parent.kind == ScanKind.SEQUENCE) {
            stack.push(new ScanFrame(ScanKind.NESTED));
            return;
        }
        if (parent.expectKey) {
            throw new ScanFallback();
        }
        String key = parent.pendingKey;
        int column = parent.pendingColumn;
        parent.pendingKey = null;
        parent.expectKey = true;
        ScanFrame frame;
        if (parent.kind == ScanKind.ROOT && "entries".equals(key)) {
            frame = new ScanFrame(ScanKind.ENTRIES);
            outline.sawEntries = true;
        } else if (parent.kind == ScanKind.ENTRIES && key != null) {
            frame = new ScanFrame(ScanKind.PLAYER);
            frame.name = key;
            frame.keyColumn = column;
            outline.playerIndent = column;
            outline.playerEnd.put(key, -1);
        } else if (parent.kind == ScanKind.PLAYER && key != null) {
            frame = new ScanFrame(ScanKind.NESTED);
            outline.characterKeys.add(parent.name + "\u0000" + key);
            if (parent.childKeyColumn < 0) {
                parent.childKeyColumn = column;
            }
        } else {
            frame = new ScanFrame(ScanKind.NESTED);
        }
        stack.push(frame);
    }

    private static void onMappingEnd(StreamOutline outline, java.util.ArrayDeque<ScanFrame> stack, Event event) {
        if (stack.isEmpty() || event.getStartMark() == null) {
            throw new ScanFallback();
        }
        ScanFrame frame = stack.pop();
        int index = event.getStartMark().getIndex();
        if (frame.kind == ScanKind.PLAYER) {
            outline.playerEnd.put(frame.name, index);
            outline.characterIndent.put(frame.name, frame.childKeyColumn < 0 ? frame.keyColumn + 2 : frame.childKeyColumn);
        } else if (frame.kind == ScanKind.ENTRIES) {
            outline.entriesEnd = index;
            if (frame.childKeyColumn >= 0) {
                outline.playerIndent = frame.childKeyColumn;
            }
        }
    }

    private static void onSequenceStart(StreamOutline outline, java.util.ArrayDeque<ScanFrame> stack) {
        ScanFrame parent = stack.peek();
        if (parent == null || parent.expectKey) {
            throw new ScanFallback();
        }
        if (parent.kind == ScanKind.PLAYER && parent.pendingKey != null) {
            outline.characterKeys.add(parent.name + "\u0000" + parent.pendingKey);
            if (parent.childKeyColumn < 0) {
                parent.childKeyColumn = parent.pendingColumn;
            }
        }
        parent.pendingKey = null;
        parent.expectKey = true;
        stack.push(new ScanFrame(ScanKind.SEQUENCE));
    }

    private static void onScalar(
            StreamOutline outline,
            java.util.ArrayDeque<ScanFrame> stack,
            ScalarEvent event,
            String text,
            MarkIndex marks) {
        ScanFrame parent = stack.peek();
        if (parent == null) {
            throw new ScanFallback();
        }
        if (parent.kind == ScanKind.SEQUENCE) {
            return;
        }
        if (parent.expectKey) {
            parent.pendingKey = scalarKey(event);
            parent.pendingColumn = keyIndent(text, event, marks);
            parent.pendingStart = event.getStartMark() == null ? 0 : event.getStartMark().getIndex();
            parent.pendingEnd = event.getEndMark() == null ? parent.pendingStart : event.getEndMark().getIndex();
            parent.expectKey = false;
            if (parent.childKeyColumn < 0) {
                parent.childKeyColumn = parent.pendingColumn;
            }
            return;
        }
        if (parent.kind == ScanKind.ROOT && "format".equals(parent.pendingKey)) {
            outline.format2 = formatNumber(event.getValue()) >= 2;
            if (event.getStartMark() != null && event.getEndMark() != null) {
                outline.sawFormat = true;
                outline.formatValueStart = event.getStartMark().getIndex();
                outline.formatValueEnd = event.getEndMark().getIndex();
            }
        } else if (parent.kind == ScanKind.PLAYER && parent.pendingKey != null) {
            if (plainNull(event)) {
                int valueEnd = event.getEndMark() == null ? parent.pendingEnd : event.getEndMark().getIndex();
                outline.nullSpans.put(parent.name + "\u0000" + parent.pendingKey,
                        new int[] {parent.pendingStart, valueEnd});
            } else {
                outline.characterKeys.add(parent.name + "\u0000" + parent.pendingKey);
                if (parent.childKeyColumn < 0) {
                    parent.childKeyColumn = parent.pendingColumn;
                }
            }
        } else if (parent.kind == ScanKind.ROOT && parent.pendingKey != null && !"entries".equals(parent.pendingKey)) {
            outline.otherRoot = true;
        }
        parent.pendingKey = null;
        parent.expectKey = true;
    }

    private static List<TextEdit> editsAbsent(
            String text,
            StreamOutline outline,
            Map<String, StoredTranscript> extra,
            boolean summaries
    ) {
        if (outline.otherRoot && !outline.sawEntries) {
            return null;
        }
        Map<String, List<Map.Entry<String, StoredTranscript>>> byPlayer = new LinkedHashMap<>();
        for (Map.Entry<String, StoredTranscript> entry : extra.entrySet()) {
            String[] parts = entry.getKey().split("\u0000", 2);
            if (parts.length != 2) {
                continue;
            }
            byPlayer.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(entry);
        }
        if (byPlayer.isEmpty()) {
            return null;
        }
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<TextEdit> edits = new ArrayList<>();
        List<Map.Entry<String, StoredTranscript>> newcomers = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, StoredTranscript>>> player : byPlayer.entrySet()) {
            List<Map.Entry<String, StoredTranscript>> inserts = new ArrayList<>();
            int indent = outline.characterIndent.getOrDefault(player.getKey(), outline.playerIndent + 2);
            for (Map.Entry<String, StoredTranscript> one : player.getValue()) {
                int[] span = outline.nullSpans.get(one.getKey());
                if (span == null) {
                    inserts.add(one);
                    continue;
                }
                String block = characterBlock(one.getKey().split("\u0000", 2)[1], one.getValue(), summaries, indent, nl);
                if (block == null) {
                    return null;
                }
                int start = cutPoint(text, span[0]);
                int end = utf16Index(text, span[1]);
                if (start < 0 || end < start || end > text.length()) {
                    return null;
                }
                edits.add(new TextEdit(start, end - start, 1, block, true));
            }
            if (inserts.isEmpty()) {
                continue;
            }
            Integer raw = outline.playerEnd.get(player.getKey());
            if (raw == null || raw < 0) {
                newcomers.addAll(inserts);
                continue;
            }
            int at = cutPoint(text, raw);
            String texts = characterTexts(inserts, summaries, indent, nl);
            if (texts == null) {
                return null;
            }
            edits.add(new TextEdit(at, 0, 1, texts, true));
        }
        if (!newcomers.isEmpty()) {
            Map<String, List<Map.Entry<String, StoredTranscript>>> fresh = new LinkedHashMap<>();
            for (Map.Entry<String, StoredTranscript> one : newcomers) {
                String playerId = one.getKey().split("\u0000", 2)[0];
                fresh.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(one);
            }
            int playerIndent = outline.sawEntries ? outline.playerIndent : 2;
            if (playerIndent < 0) {
                playerIndent = 2;
            }
            int characterIndent = playerIndent + 2;
            StringBuilder block = new StringBuilder();
            if (!outline.sawEntries) {
                block.append("entries:").append(nl);
            }
            for (Map.Entry<String, List<Map.Entry<String, StoredTranscript>>> player : fresh.entrySet()) {
                String playerKey = yamlKey(player.getKey());
                String texts = characterTexts(player.getValue(), summaries, characterIndent, nl);
                if (playerKey == null || texts == null) {
                    return null;
                }
                block.append(" ".repeat(playerIndent)).append(playerKey).append(':').append(nl);
                block.append(texts);
            }
            if (outline.sawEntries && outline.entriesEnd < 0) {
                return null;
            }
            int at = outline.sawEntries ? cutPoint(text, outline.entriesEnd) : text.length();
            edits.add(new TextEdit(at, 0, 2, block.toString(), true));
        }
        if (summaries && !outline.format2) {
            if (outline.sawFormat && outline.formatValueEnd > outline.formatValueStart) {
                int start = utf16Index(text, outline.formatValueStart);
                int end = utf16Index(text, outline.formatValueEnd);
                if (start < 0 || end < start || end > text.length()) {
                    return null;
                }
                edits.add(new TextEdit(start, end - start, 0, "2", false));
            } else {
                edits.add(new TextEdit(0, 0, 0, "format: 2" + nl, true));
            }
        }
        return edits;
    }

    /**
     * Event marks are code-point offsets. A block-end mark also sits on the next token, after
     * that line's indent, so the cut moves back to the start of the line and the following key
     * keeps its indent.
     */
    private static int cutPoint(String text, int offset) {
        int at = utf16Index(text, offset);
        while (at > 0) {
            char previous = text.charAt(at - 1);
            if (previous != ' ' && previous != '\t') {
                break;
            }
            at--;
        }
        return at;
    }

    private static int utf16Index(String text, int codePoints) {
        int unit = 0;
        int seen = 0;
        int length = text.length();
        while (unit < length && seen < codePoints) {
            unit += Character.charCount(text.codePointAt(unit));
            seen++;
        }
        return unit;
    }

    private static String characterTexts(
            List<Map.Entry<String, StoredTranscript>> characters,
            boolean summaries,
            int indent,
            String nl
    ) {
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, StoredTranscript> one : characters) {
            String character = one.getKey().split("\u0000", 2)[1];
            String text = characterBlock(character, one.getValue(), summaries, indent, nl);
            if (text == null) {
                return null;
            }
            block.append(text);
            if (!block.isEmpty() && block.charAt(block.length() - 1) != '\n' && block.charAt(block.length() - 1) != '\r') {
                block.append(nl);
            }
        }
        return block.toString();
    }

    private static int formatNumber(String rest) {
        try {
            return Integer.parseInt(rest == null ? "" : rest.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int unsavedCharacterCount(boolean summaries) {
        synchronized (this) {
            return transcriptsAbsent(Set.of(), summaries).size();
        }
    }

    private static void warnUnsaved(Logger logger, int count, SkipReason reason) {
        if (logger == null || count <= 0 || reason == null) {
            return;
        }
        logger.warning("Did not save " + count + " dialogue characters because " + reason.text + ".");
    }

    /**
     * Column of a key. A complex key ({@code ?} followed by whitespace) is measured at the {@code ?},
     * not at the scalar, so a later character is inserted beside it.
     */
    private static int keyIndent(String text, ScalarEvent event, MarkIndex marks) {
        if (event.getStartMark() == null) {
            return 0;
        }
        int column = event.getStartMark().getColumn();
        int at = marks.utf16(text, event.getStartMark().getIndex());
        int line = at;
        while (line > 0) {
            char previous = text.charAt(line - 1);
            if (previous == '\n' || previous == '\r') {
                break;
            }
            line--;
        }
        int content = line;
        while (content < text.length()) {
            char c = text.charAt(content);
            if (c != ' ' && c != '\t') {
                break;
            }
            content++;
        }
        if (content < text.length() && text.charAt(content) == '?' && content + 1 < text.length()) {
            char after = text.charAt(content + 1);
            if (after == ' ' || after == '\t' || after == '\n' || after == '\r') {
                return content - line;
            }
        }
        return column;
    }

    private enum ScanKind {
        ROOT, ENTRIES, PLAYER, NESTED, SEQUENCE
    }

    /**
     * Event marks are code points and arrive in order. This index turns the next mark into a
     * Java index without rereading the file from the start.
     */
    private static final class MarkIndex {
        private int codePoint;
        private int unit;

        private int utf16(String text, int codePointIndex) {
            if (codePointIndex < codePoint) {
                codePoint = 0;
                unit = 0;
            }
            int length = text.length();
            while (unit < length && codePoint < codePointIndex) {
                unit += Character.charCount(text.codePointAt(unit));
                codePoint++;
            }
            return unit;
        }
    }

    private static final class ScanFrame {
        private final ScanKind kind;
        private String name = "";
        private boolean expectKey = true;
        private String pendingKey;
        private int pendingColumn;
        private int pendingStart;
        private int pendingEnd;
        private int keyColumn;
        private int childKeyColumn = -1;

        private ScanFrame(ScanKind kind) {
            this.kind = kind;
            this.expectKey = kind != ScanKind.SEQUENCE;
        }
    }

    private static final class StreamOutline {
        private boolean format2;
        private boolean sawFormat;
        private int formatValueStart = -1;
        private int formatValueEnd = -1;
        private boolean sawEntries;
        private boolean otherRoot;
        /** Index of the entries mapping end, or -1 when that mapping was not closed. */
        private int entriesEnd = -1;
        private int playerIndent = 2;
        private final Map<String, Integer> playerEnd = new LinkedHashMap<>();
        private final Map<String, Integer> characterIndent = new LinkedHashMap<>();
        private final Set<String> characterKeys = new java.util.HashSet<>();
        /** Empty character values a later load drops, as code-point start and end of that key. */
        private final Map<String, int[]> nullSpans = new LinkedHashMap<>();
    }

    /**
     * @param pad when true, a missing line break is written around {@code text}. A format replacement
     *            is not padded.
     */
    private record TextEdit(int at, int delete, int rank, String text, boolean pad) {
    }

    private static final class ScanFallback extends RuntimeException {
        private ScanFallback() {
            super(null, null, false, false);
        }
    }

    /**
     * One character mapping, or null when this character cannot be written as a block that loads
     * back as the same id and lines. A failure is this character only; the caller rewrites the
     * document instead of dropping the other characters.
     */
    private static String characterBlock(
            String characterId, StoredTranscript transcript, boolean summaries, int indent, String nl) {
        if (yamlKey(characterId) != null && !transcriptNeedsQuote(transcript)) {
            String classic = classicCharacterBlock(characterId, transcript, summaries, indent, nl);
            if (blockRoundTrips(characterId, transcript, summaries, classic)) {
                return classic;
            }
        }
        String dumped = dumpedCharacterBlock(characterId, transcript, summaries, indent, nl, false);
        if (blockRoundTrips(characterId, transcript, summaries, dumped)) {
            return dumped;
        }
        String quoted = dumpedCharacterBlock(characterId, transcript, summaries, indent, nl, true);
        if (blockRoundTrips(characterId, transcript, summaries, quoted)) {
            return quoted;
        }
        return null;
    }

    private static boolean transcriptNeedsQuote(StoredTranscript transcript) {
        for (TurnMemory.Line line : transcript.lines) {
            if (needsQuote(line.text())) {
                return true;
            }
        }
        return needsQuote(transcript.summary);
    }

    /**
     * The same block {@code yaml.saveToString()} writes, indented under the character key.
     * Multi-line text stays a literal block.
     */
    private static String classicCharacterBlock(
            String characterId, StoredTranscript transcript, boolean summaries, int indent, String nl) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("updated", transcript.updatedAt);
        if (!transcript.lines.isEmpty()) {
            List<Map<String, String>> stored = new ArrayList<>();
            for (TurnMemory.Line line : transcript.lines) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", line.role());
                row.put("text", line.text());
                stored.add(row);
            }
            yaml.set("lines", stored);
        }
        if (summaries && transcript.summary != null && !transcript.summary.isBlank()) {
            yaml.set("summary", transcript.summary);
            yaml.set("summary-updated", transcript.summaryUpdatedAt);
        }
        String dumped = yaml.saveToString().replace("\r\n", "\n").replace("\r", "\n");
        if (dumped.endsWith("\n")) {
            dumped = dumped.substring(0, dumped.length() - 1);
        }
        String pad = " ".repeat(indent + 2);
        StringBuilder block = new StringBuilder();
        block.append(" ".repeat(indent)).append(yamlKey(characterId)).append(':').append(nl);
        for (String line : dumped.split("\n", -1)) {
            if (line.isEmpty()) {
                block.append(nl);
                continue;
            }
            block.append(pad).append(line).append(nl);
        }
        return block.toString();
    }

    /**
     * One character, emitted directly. Ordinary multi-line text stays a literal block. Strings that
     * would break that block are double-quoted, and {@code quoteAll} quotes every string.
     */
    private static String dumpedCharacterBlock(
            String characterId,
            StoredTranscript transcript,
            boolean summaries,
            int indent,
            String nl,
            boolean quoteAll
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", transcript.updatedAt);
        if (!transcript.lines.isEmpty()) {
            List<Map<String, String>> stored = new ArrayList<>();
            for (TurnMemory.Line line : transcript.lines) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", line.role());
                row.put("text", line.text());
                stored.add(row);
            }
            body.put("lines", stored);
        }
        if (summaries && transcript.summary != null && !transcript.summary.isBlank()) {
            body.put("summary", transcript.summary);
            body.put("summary-updated", transcript.summaryUpdatedAt);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(characterId, body);
        StringBuilder block = new StringBuilder();
        appendIndented(block, dumpDocument(wrapped, quoteAll), " ".repeat(indent), nl);
        return block.toString();
    }

    /**
     * Block mapping whose strings stay strings. A plain scalar is used only when YAML 1.1 would
     * read it back as that same string. Ordinary multi-line text is a literal block. Controls are
     * escaped inside double quotes.
     */
    private static String dumpDocument(Map<String, Object> document) {
        return dumpDocument(document, false);
    }

    private static String dumpDocument(Map<String, Object> document, boolean quoteAll) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setMaxSimpleKeyLength(MAX_SIMPLE_KEY);
        Representer representer = new Representer(options) {
            {
                representers.put(String.class, data -> {
                    String value = String.valueOf(data);
                    DumperOptions.ScalarStyle style;
                    if (quoteAll || needsQuote(value)) {
                        style = DumperOptions.ScalarStyle.DOUBLE_QUOTED;
                    } else if (value.indexOf('\n') >= 0) {
                        style = DumperOptions.ScalarStyle.LITERAL;
                    } else {
                        style = null;
                    }
                    return representScalar(Tag.STR, value, style);
                });
            }
        };
        return new Yaml(representer, options).dump(document);
    }

    /** Long enough that a character id is a simple key instead of a {@code ?} key. */
    private static final int MAX_SIMPLE_KEY = 1024;

    private static void allowLongSimpleKeys(YamlConfiguration yaml) {
        try {
            Field field = YamlConfiguration.class.getDeclaredField("yamlDumperOptions");
            field.setAccessible(true);
            Object options = field.get(yaml);
            if (options instanceof DumperOptions dumper) {
                dumper.setMaxSimpleKeyLength(MAX_SIMPLE_KEY);
            }
        } catch (ReflectiveOperationException ignored) {
            // A long id then stays a complex key. The scan measures that key at the question mark.
        }
    }

    private static Map<String, Object> configurationMap(YamlConfiguration yaml) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            root.put(key, plainValue(yaml.get(key)));
        }
        return root;
    }

    private static Object plainValue(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) {
                map.put(key, plainValue(section.get(key)));
            }
            return map;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), plainValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object one : list) {
                copy.add(plainValue(one));
            }
            return copy;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value;
    }

    /**
     * Ordinary newlines and tabs stay in a literal block. These characters would split that block
     * or be dropped, so they are written as double-quoted escapes.
     */
    private static boolean needsQuote(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t') {
                continue;
            }
            if (c == '\u0085' || c == '\u2028' || c == '\u2029' || c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsForcedQuote(Object value) {
        if (value instanceof String text) {
            return needsQuote(text);
        }
        if (value instanceof ConfigurationSection section) {
            for (Object child : section.getValues(false).values()) {
                if (containsForcedQuote(child)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object child : map.values()) {
                if (containsForcedQuote(child)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object child : list) {
                if (containsForcedQuote(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Indents every physical line of a YAML dump. U+0085, U+2028, and U+2029 stay in place
     * when the dump emits them raw, and the following line still receives {@code pad}.
     */
    private static void appendIndented(StringBuilder block, String dumped, String pad, String nl) {
        int i = 0;
        int n = dumped.length();
        boolean atLineStart = true;
        while (i < n) {
            char c = dumped.charAt(i);
            if (c == '\r' || c == '\n') {
                int next = i + 1;
                if (c == '\r' && next < n && dumped.charAt(next) == '\n') {
                    next++;
                }
                if (next < n) {
                    block.append(nl);
                    atLineStart = true;
                }
                i = next;
                continue;
            }
            if (c == '\u0085' || c == '\u2028' || c == '\u2029') {
                block.append(c);
                atLineStart = true;
                i++;
                continue;
            }
            if (atLineStart) {
                block.append(pad);
                atLineStart = false;
            }
            block.append(c);
            i++;
        }
    }

    private static boolean blockRoundTrips(
            String characterId, StoredTranscript transcript, boolean summaries, String block) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(block);
        } catch (InvalidConfigurationException | RuntimeException e) {
            return false;
        }
        if (!yaml.getKeys(false).contains(characterId)) {
            return false;
        }
        Object updated = yaml.get(characterId + ".updated");
        if (!(updated instanceof Number) || ((Number) updated).longValue() != transcript.updatedAt) {
            return false;
        }
        List<Map<?, ?>> rows = yaml.getMapList(characterId + ".lines");
        if (rows.size() != transcript.lines.size()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            TurnMemory.Line line = transcript.lines.get(i);
            Map<?, ?> row = rows.get(i);
            if (!String.valueOf(line.role()).equals(String.valueOf(row.get("role")))) {
                return false;
            }
            if (!String.valueOf(line.text()).equals(String.valueOf(row.get("text")))) {
                return false;
            }
        }
        if (summaries && transcript.summary != null && !transcript.summary.isBlank()) {
            if (!transcript.summary.equals(yaml.getString(characterId + ".summary"))) {
                return false;
            }
            Object summaryUpdated = yaml.get(characterId + ".summary-updated");
            if (!(summaryUpdated instanceof Number)
                    || ((Number) summaryUpdated).longValue() != transcript.summaryUpdatedAt) {
                return false;
            }
        }
        return true;
    }

    private Map<String, StoredTranscript> redactExtra(Map<String, StoredTranscript> extra, Iterable<String> secrets) {
        Map<String, StoredTranscript> masked = new LinkedHashMap<>();
        for (Map.Entry<String, StoredTranscript> entry : extra.entrySet()) {
            StoredTranscript transcript = entry.getValue();
            List<TurnMemory.Line> lines = new ArrayList<>();
            for (TurnMemory.Line line : transcript.lines) {
                lines.add(new TurnMemory.Line(line.role(), SecretMask.redact(line.text(), secrets)));
            }
            String summary = transcript.summary == null ? "" : SecretMask.redact(transcript.summary, secrets);
            masked.put(entry.getKey(), new StoredTranscript(lines, transcript.updatedAt, summary, transcript.summaryUpdatedAt));
        }
        return masked;
    }

    private WriteResult writeAppended(
            File file,
            String text,
            List<TextEdit> edits,
            Logger logger,
            boolean summaries,
            boolean format2,
            Iterable<String> secrets,
            long deadlineNanos
    ) {
        if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
            return WriteResult.PAST_DEADLINE;
        }
        if (summaries && !format2) {
            try {
                java.nio.file.Path backup = FileBackup.backup(file.toPath());
                if (logger != null) {
                    logger.info("Backed up dialogue-memory.yml to " + backup.getFileName());
                }
            } catch (IOException e) {
                if (logger != null) {
                    LogRedaction.warning(logger, "Failed to back up dialogue-memory.yml", e, secrets);
                }
                return WriteResult.FAILED;
            }
        }
        File parent = file.getParentFile();
        File temporary = new File(parent == null ? new File(".") : parent,
                file.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return WriteResult.FAILED;
            }
            AtomicFiles.createPrivate(temporary.toPath());
            writeMerged(temporary.toPath(), text, edits);
            WriteResult gate = gateReplace(deadlineNanos);
            if (gate != WriteResult.PUBLISHED) {
                return gate;
            }
            durableReplace(temporary.toPath(), file.toPath());
            return WriteResult.PUBLISHED;
        } catch (IOException e) {
            if (logger != null) {
                LogRedaction.warning(logger, "Failed to save dialogue-memory.yml", e, secrets);
            }
            return WriteResult.FAILED;
        } finally {
            if (temporary.isFile() && !temporary.equals(file)) {
                temporary.delete();
            }
        }
    }

    /**
     * Copies {@code text} and applies {@code edits} without building a second copy of the file.
     * Unchanged characters are written through one buffer. The caller syncs the temporary file
     * and renames it.
     */
    private static void writeMerged(Path temporary, String text, List<TextEdit> edits) throws IOException {
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort((left, right) -> {
            int byIndex = Integer.compare(left.at, right.at);
            return byIndex != 0 ? byIndex : Integer.compare(left.rank, right.rank);
        });
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        try (Writer writer = new OutputStreamWriter(
                new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 20),
                StandardCharsets.UTF_8)) {
            int copied = 0;
            int lastAt = -1;
            for (TextEdit edit : ordered) {
                if (edit.at < copied || edit.at > text.length() || edit.delete < 0) {
                    throw new IOException("dialogue-memory edit is out of range");
                }
                int end = edit.at + edit.delete;
                if (end > text.length()) {
                    throw new IOException("dialogue-memory edit is out of range");
                }
                writer.write(text, copied, edit.at - copied);
                copied = end;
                String block = edit.text == null ? "" : edit.text;
                if (block.isEmpty()) {
                    continue;
                }
                if (edit.pad) {
                    boolean boundary = edit.at != lastAt && edit.at > 0
                            && text.charAt(edit.at - 1) != '\n'
                            && text.charAt(edit.at - 1) != '\r';
                    if (boundary && !block.startsWith("\n") && !block.startsWith("\r")) {
                        writer.write(nl);
                    }
                    if (!block.endsWith("\n")) {
                        block = block + nl;
                    }
                }
                writer.write(block);
                lastAt = edit.at;
            }
            if (copied > text.length()) {
                throw new IOException("dialogue-memory edit is out of range");
            }
            writer.write(text, copied, text.length() - copied);
        }
    }

    /**
     * A plain key when a later load would keep that same string, otherwise a single-quoted key.
     * Plain YAML 1.1 words such as {@code on} and {@code 007} are quoted. Null when the key cannot
     * be written as a simple key.
     */
    private static String yamlKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 1024) {
            return null;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return null;
            }
        }
        if (key.matches("[A-Za-z0-9_.-]+") && Tag.STR.equals(PLAIN.resolve(NodeId.scalar, key, true))) {
            return key;
        }
        return "'" + key.replace("'", "''") + "'";
    }

    private static final Resolver PLAIN = new Resolver();
    private static final Yaml PLAIN_SCALAR = new Yaml();

    /**
     * The key a later load stores. A quoted scalar stays as written. A plain scalar that YAML 1.1
     * would turn into a boolean or a number is the text of that value.
     */
    private static String scalarKey(ScalarEvent event) {
        String raw = event.getValue() == null ? "" : event.getValue();
        if (event.getTag() != null && !Tag.STR.getValue().equals(event.getTag())) {
            throw new ScanFallback();
        }
        String key;
        if (event.getImplicit() == null || !event.getImplicit().canOmitTagInPlainScalar()) {
            key = raw;
        } else {
            Tag tag = PLAIN.resolve(NodeId.scalar, raw, true);
            if (Tag.STR.equals(tag)) {
                key = raw;
            } else if (Tag.NULL.equals(tag)) {
                throw new ScanFallback();
            } else {
                key = plainScalar(raw);
                if (key == null) {
                    throw new ScanFallback();
                }
            }
        }
        // A mapping whose key is == is a serialized object, which a later load rejects.
        if ("==".equals(key)) {
            throw new ScanFallback();
        }
        return key;
    }

    private static synchronized String plainScalar(String raw) {
        Object value;
        try {
            value = PLAIN_SCALAR.load(raw);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
            return null;
        }
        return String.valueOf(value);
    }

    /** A plain scalar a later load drops, such as an empty {@code npc:} line. */
    private static boolean plainNull(ScalarEvent event) {
        if (event.getValue() == null) {
            return true;
        }
        if (event.getImplicit() == null || !event.getImplicit().canOmitTagInPlainScalar()) {
            return false;
        }
        return Tag.NULL.equals(PLAIN.resolve(NodeId.scalar, event.getValue(), true));
    }

    static Set<String> characterKeys(YamlConfiguration yaml) {
        Set<String> keys = new java.util.HashSet<>();
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) {
            return keys;
        }
        for (String playerKey : entries.getKeys(false)) {
            ConfigurationSection characters = entries.getConfigurationSection(playerKey);
            if (characters == null) {
                continue;
            }
            for (String characterId : characters.getKeys(false)) {
                keys.add(playerKey + "\u0000" + characterId);
            }
        }
        return keys;
    }

    private Map<String, StoredTranscript> transcriptsAbsent(Set<String> present, boolean summaries) {
        Map<String, StoredTranscript> extra = new LinkedHashMap<>();
        for (Map.Entry<String, TurnMemory> entry : memories.entrySet()) {
            if (present.contains(entry.getKey())) {
                continue;
            }
            TurnMemory memory = entry.getValue();
            List<TurnMemory.Line> lines = memory.view();
            String summary = summaries ? memory.summary() : "";
            if (lines.isEmpty() && (summary == null || summary.isBlank())) {
                continue;
            }
            extra.put(entry.getKey(), new StoredTranscript(
                    lines, memory.updatedAt(), summary == null ? "" : summary, memory.summaryUpdatedAt()));
        }
        return extra;
    }

    private static void writeTranscript(YamlConfiguration yaml, String key, StoredTranscript transcript, boolean summaries) {
        String[] parts = key.split("\u0000", 2);
        if (parts.length != 2) {
            return;
        }
        String base = "entries." + parts[0] + "." + parts[1];
        yaml.set(base + ".updated", transcript.updatedAt);
        if (!transcript.lines.isEmpty()) {
            List<Map<String, String>> stored = new ArrayList<>();
            for (TurnMemory.Line line : transcript.lines) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", line.role());
                row.put("text", line.text());
                stored.add(row);
            }
            yaml.set(base + ".lines", stored);
        }
        if (summaries && !transcript.summary.isBlank()) {
            yaml.set(base + ".summary", transcript.summary);
            yaml.set(base + ".summary-updated", transcript.summaryUpdatedAt);
        }
    }

    private record StoredTranscript(List<TurnMemory.Line> lines, long updatedAt, String summary, long summaryUpdatedAt) {
    }

    private YamlConfiguration document(boolean summaries) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (summaries) {
            yaml.set("format", 2);
        }
        for (var entry : memories.entrySet()) {
            String[] parts = entry.getKey().split("\u0000", 2);
            if (parts.length != 2) {
                continue;
            }
            TurnMemory memory = entry.getValue();
            List<TurnMemory.Line> lines = memory.view();
            String storedSummary = summaries ? memory.summary() : "";
            if (lines.isEmpty() && storedSummary.isBlank()) {
                continue;
            }
            String base = "entries." + parts[0] + "." + parts[1];
            yaml.set(base + ".updated", memory.updatedAt());
            if (!lines.isEmpty()) {
                List<java.util.Map<String, String>> stored = new ArrayList<>();
                for (TurnMemory.Line line : lines) {
                    java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                    row.put("role", line.role());
                    row.put("text", line.text());
                    stored.add(row);
                }
                yaml.set(base + ".lines", stored);
            }
            if (summaries && !storedSummary.isBlank()) {
                yaml.set(base + ".summary", storedSummary);
                yaml.set(base + ".summary-updated", memory.summaryUpdatedAt());
            }
        }
        return yaml;
    }

    private static boolean hasFormat2(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return false;
        }
        return yaml.getInt("format", 0) >= 2;
    }

    private static void moveIntoPlace(File temporary, File target) throws IOException {
        durableReplace(temporary.toPath(), target.toPath());
    }

    /**
     * Syncs the temp file, renames it onto the target, then syncs the directory.
     * Either fsync failing is ignored: the rename still happens, and the caller's {@code finally}
     * removes a temp file that was not moved. Permissions stay with {@link AtomicFiles#moveReplacing}.
     */
    static void durableReplace(Path temporary, Path target) throws IOException {
        traceDurable("force-temp");
        try {
            diskSync.force(temporary);
        } catch (IOException ignored) {
            // A failed fsync must not abort the rename or leave the temp file behind.
        }
        traceDurable("rename");
        AtomicFiles.moveReplacing(temporary, target);
        Path parent = target == null || target.getParent() == null ? Path.of(".") : target.getParent();
        traceDurable("force-dir");
        try {
            diskSync.force(parent);
        } catch (IOException ignored) {
            // Directory fsync is best effort, the same as after a quarantine rename.
        }
    }

    private static void traceDurable(String event) {
        List<String> trace = durableTrace;
        if (trace == null) {
            return;
        }
        synchronized (trace) {
            trace.add(event);
        }
    }

    private static void forceFile(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @FunctionalInterface
    interface Publish {
        void publish(File temporary, File target) throws IOException;
    }

    /**
     * Copies an unreadable file to {@code dialogue-memory.yml.corrupt} with keys masked and mode
     * {@code 0600}, then removes the live file so a later save cannot replace it or back it up raw.
     * The copy is written to a temp sibling, forced to disk, and renamed, so a crash leaves either
     * a complete {@code .corrupt} or no {@code .corrupt} at all.
     * When a {@code .corrupt} file already holds those same masked bytes, the original is removed
     * and no second copy is written.
     *
     * @return {@code true} when the broken text is preserved aside and the live path is free
     */
    private static boolean quarantineUnreadable(
            File file, Iterable<String> secrets, Logger logger, Exception error, MemoryStore store) {
        String detail = SecretMask.redact(
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                secrets);
        try {
            byte[] raw = Files.readAllBytes(file.toPath());
            Preserved preserved = preserveBytes(raw, secrets);
            if (store == null) {
                return publishQuarantine(file, secrets, logger, detail, preserved);
            }
            synchronized (store.publishGate) {
                if (store.loaderMustNotPublish) {
                    return true;
                }
                return publishQuarantine(file, secrets, logger, detail, preserved);
            }
        } catch (IOException io) {
            if (store != null) {
                synchronized (store.publishGate) {
                    if (store.loaderMustNotPublish) {
                        return true;
                    }
                    return leaveUnreadableInPlace(file, secrets, logger, detail, io);
                }
            }
            return leaveUnreadableInPlace(file, secrets, logger, detail, io);
        }
    }

    private static boolean publishQuarantine(
            File file, Iterable<String> secrets, Logger logger, String detail, Preserved preserved) throws IOException {
        Path existing = findIdenticalCorrupt(file.toPath(), preserved.bytes());
        if (existing != null) {
            return reuseCorruptCopy(file, logger, detail, preserved, existing);
        }
        Path copy = corruptDestination(file.toPath());
        writeCorruptAtomically(copy, preserved.bytes());
        try {
            Files.delete(file.toPath());
        } catch (IOException deleteFailed) {
            return maskedInPlace(file, logger, detail, preserved.bytes(), copy);
        }
        if (logger != null) {
            logger.warning("dialogue-memory.yml was not loaded: " + detail
                    + ". " + preserved.note() + " The broken file was saved as " + copy.getFileName()
                    + " with owner-only permissions. Repair that copy, replace dialogue-memory.yml with it, and restart."
                    + " A new dialogue-memory.yml will be written for new lines and will not replace the copy.");
        }
        return true;
    }

    /**
     * The original survived a crash between the {@code .corrupt} rename and its own delete.
     * A byte-identical copy is enough; writing another one only piles up duplicates.
     */
    private static boolean reuseCorruptCopy(
            File file, Logger logger, String detail, Preserved preserved, Path existing) throws IOException {
        try {
            Files.delete(file.toPath());
        } catch (IOException deleteFailed) {
            return maskedInPlace(file, logger, detail, preserved.bytes(), existing);
        }
        if (logger != null) {
            logger.warning("dialogue-memory.yml was not loaded: " + detail
                    + ". " + preserved.note() + " An identical copy already exists as " + existing.getFileName()
                    + ". The original was removed. Repair that copy, replace dialogue-memory.yml with it, and restart."
                    + " A new dialogue-memory.yml will be written for new lines and will not replace the copy.");
        }
        return true;
    }

    private static boolean maskedInPlace(File file, Logger logger, String detail, byte[] masked, Path copy) throws IOException {
        Files.write(file.toPath(), masked);
        AtomicFiles.restrictOwnerReadWrite(file.toPath());
        if (logger != null) {
            logger.warning("dialogue-memory.yml was not loaded: " + detail
                    + ". It could not be moved aside, so it was masked in place as well as copied to "
                    + copy.getFileName()
                    + ". It will not be overwritten. Repair that file and restart.");
        }
        return false;
    }

    private static boolean leaveUnreadableInPlace(
            File file, Iterable<String> secrets, Logger logger, String detail, IOException io) {
        AtomicFiles.restrictOwnerReadWrite(file.toPath());
        if (logger != null) {
            String cause = io.getClass().getSimpleName();
            String ioMessage = io.getMessage();
            if (ioMessage != null && !ioMessage.isBlank()) {
                cause = cause + ": " + SecretMask.redact(ioMessage, secrets);
            }
            logger.warning("dialogue-memory.yml was not loaded: " + detail
                    + ". The broken file was left in place because it could not be copied aside"
                    + " (" + cause + ")."
                    + " It was restricted to owner-only permissions and it will not be overwritten."
                    + " Repair or replace dialogue-memory.yml and restart.");
        }
        return false;
    }

    /**
     * A {@code .corrupt} or {@code .corrupt.<millis>} sibling whose bytes equal {@code masked}.
     * The plain {@code .corrupt} name wins when both match. Listing failures fall through to a new copy.
     */
    private static Path findIdenticalCorrupt(Path file, byte[] masked) {
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        String prefix = file.getFileName().toString() + ".corrupt";
        Path stamped = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path candidate : stream) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String name = candidate.getFileName().toString();
                if (!name.equals(prefix) && !name.startsWith(prefix + ".")) {
                    continue;
                }
                if (!Arrays.equals(masked, Files.readAllBytes(candidate))) {
                    continue;
                }
                if (name.equals(prefix)) {
                    return candidate;
                }
                if (stamped == null) {
                    stamped = candidate;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return stamped;
    }

    private record Preserved(byte[] bytes, String note) {
    }

    /**
     * Masks keys in UTF-8 text. A file that is not valid UTF-8 is kept byte for byte, with ASCII
     * keys masked, so the original is not dropped on the floor.
     */
    private static Preserved preserveBytes(byte[] raw, Iterable<String> secrets) {
        try {
            String text = decodeUtf8(raw);
            byte[] masked = SecretMask.redact(text, secrets).getBytes(StandardCharsets.UTF_8);
            return new Preserved(masked, "API keys in it were masked.");
        } catch (CharacterCodingException notUtf8) {
            String latin = new String(raw, StandardCharsets.ISO_8859_1);
            byte[] masked = SecretMask.redact(latin, secrets).getBytes(StandardCharsets.ISO_8859_1);
            return new Preserved(masked,
                    "The file is not valid UTF-8, so the copy keeps the original bytes and masks ASCII keys where they could be found.");
        }
    }

    private static String decodeUtf8(byte[] raw) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(raw)).toString();
    }

    /**
     * Writes {@code bytes} to a private temp file, forces that file to disk, then renames it onto
     * {@code destination}. {@code destination} does not exist until the rename.
     */
    private static void writeCorruptAtomically(Path destination, byte[] bytes) throws IOException {
        Path parent = destination.getParent() == null ? Path.of(".") : destination.getParent();
        Path temporary = parent.resolve("dialogue-memory.yml." + UUID.randomUUID() + ".tmp");
        AtomicFiles.createPrivate(temporary);
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            AtomicFiles.restrictOwnerReadWrite(temporary);
            corruptMove.move(temporary, destination);
            fsyncBestEffort(destination);
            fsyncBestEffort(parent);
        } finally {
            if (!temporary.equals(destination)) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void renameCorrupt(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void fsyncBestEffort(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
            // A directory on some file systems cannot be forced. The temp file was already forced.
        }
    }

    private static Path corruptDestination(Path file) {
        Path parent = file.getParent() == null ? Path.of(".") : file.getParent();
        Path first = parent.resolve(file.getFileName() + ".corrupt");
        if (!Files.exists(first)) {
            return first;
        }
        Path stamped = parent.resolve(file.getFileName() + ".corrupt." + System.currentTimeMillis());
        int extra = 0;
        while (Files.exists(stamped) && extra < 100) {
            extra++;
            stamped = parent.resolve(file.getFileName() + ".corrupt." + System.currentTimeMillis() + "-" + extra);
        }
        return stamped;
    }

    private static void sweepStaleTemps(File file, Logger logger) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            return;
        }
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        int removed = 0;
        for (File child : children) {
            if (!child.isFile() || !STALE_TEMP.matcher(child.getName()).matches()) {
                continue;
            }
            try {
                if (Files.deleteIfExists(child.toPath())) {
                    removed++;
                }
            } catch (IOException e) {
                if (logger != null) {
                    logger.warning("Could not remove stale temp file " + child.getName());
                }
            }
        }
        if (removed > 0 && logger != null) {
            logger.info("Removed " + removed
                    + " stale dialogue-memory.yml temp file(s) left after a save was interrupted.");
        }
    }

    private static String key(UUID player, String characterId) {
        return player + "\u0000" + (characterId == null ? "" : characterId);
    }

    private static boolean bytesMatch(File file, Snapshot snap) throws IOException {
        if (file == null || snap == null || snap.bytes == null) {
            return false;
        }
        if (file.length() != snap.bytes.length) {
            return false;
        }
        return Arrays.equals(snap.bytes, Files.readAllBytes(file.toPath()));
    }

    private static Snapshot readSnapshot(File file, AbsentScan scan) throws IOException {
        if (file == null || !file.isFile()) {
            return Snapshot.EMPTY;
        }
        byte[] bytes = readBytes(file, scan);
        String text;
        try {
            text = decodeUtf8(bytes);
        } catch (CharacterCodingException e) {
            return new Snapshot(bytes, null, null, true);
        }
        EventRead read = parseEvents(text, scan);
        return new Snapshot(bytes, text, read.outline, read.unreadable);
    }

    private static byte[] readBytes(File file, AbsentScan scan) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size > Integer.MAX_VALUE) {
                throw new IOException("dialogue-memory.yml is too large");
            }
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (scan != null && scan.abandoned) {
                    throw new LoadAbandoned();
                }
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
            if (scan != null && scan.abandoned) {
                throw new LoadAbandoned();
            }
            return bytes;
        }
    }

    private static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(null, null, null, false);
        private final byte[] bytes;
        private final String text;
        private final StreamOutline outline;
        private final boolean unreadable;

        private Snapshot(byte[] bytes, String text, StreamOutline outline, boolean unreadable) {
            this.bytes = bytes;
            this.text = text;
            this.outline = outline;
            this.unreadable = unreadable;
        }
    }

    private static final class EventRead {
        private final StreamOutline outline;
        private final boolean unreadable;

        private EventRead(StreamOutline outline, boolean unreadable) {
            this.outline = outline;
            this.unreadable = unreadable;
        }

        private static EventRead ok(StreamOutline outline) {
            return new EventRead(outline, false);
        }

        private static EventRead refused() {
            return new EventRead(null, false);
        }

        private static EventRead unreadable() {
            return new EventRead(null, true);
        }
    }

    /** Stops a file read without treating the file as broken. */
    private static final class LoadAbandoned extends IOException {
        private LoadAbandoned() {
            super("dialogue-memory read stopped");
        }
    }

    /**
     * A file stream that stops when stop gives up on the load. The next read fails and the
     * load returns without quarantining the file.
     */
    private static final class AbandonableInputStream extends FilterInputStream {
        private AbandonableInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            if (abandonActiveLoad) {
                throw new LoadAbandoned();
            }
            return super.read();
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (abandonActiveLoad) {
                throw new LoadAbandoned();
            }
            return super.read(destination, offset, length);
        }
    }

    /**
     * Key scan started when stop begins. The bytes are the file as it was at that moment.
     * Stop uses them only when the live file is still those bytes.
     */
    static final class AbsentScan implements Runnable {
        private final File file;
        private final Thread thread;
        private volatile Snapshot snapshot = Snapshot.EMPTY;
        private volatile boolean crashed;
        private volatile boolean finished;
        private volatile boolean abandoned;

        private AbsentScan(File file) {
            this.file = file;
            this.thread = new Thread(this, "nexusai-memory-keys");
            this.thread.setDaemon(true);
        }

        private static AbsentScan start(File file) {
            AbsentScan scan = new AbsentScan(file);
            scan.thread.start();
            return scan;
        }

        /**
         * Waits until this scan has finished or {@code deadlineNanos} has passed. A scan that is
         * still running is not used.
         */
        private Snapshot awaitSnapshot(long deadlineNanos) {
            while (!finished) {
                long left = deadlineNanos - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                try {
                    thread.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (thread.isAlive() && !finished && System.nanoTime() >= deadlineNanos) {
                    return null;
                }
            }
            return snapshot;
        }

        private boolean crashed() {
            return crashed;
        }

        private void releaseAll() {
            snapshot = Snapshot.EMPTY;
        }

        private void abandonAndJoin() {
            abandoned = true;
            snapshot = Snapshot.EMPTY;
            thread.interrupt();
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            try {
                Runnable hook = beforeKeyScan;
                if (hook != null) {
                    hook.run();
                }
                if (abandoned) {
                    return;
                }
                snapshot = readSnapshot(file, this);
            } catch (LoadAbandoned ignored) {
                snapshot = Snapshot.EMPTY;
            } catch (Throwable ignored) {
                crashed = true;
                snapshot = Snapshot.EMPTY;
            } finally {
                finished = true;
            }
        }
    }
}
