package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.FileBackup;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.config.SecretMask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Replaces the atomic rename of a quarantine copy. Tests use this to stop after the temp file
     * is complete and before {@code .corrupt} exists.
     */
    static CorruptMove corruptMove = MemoryStore::renameCorrupt;

    @FunctionalInterface
    interface CorruptMove {
        void move(Path temporary, Path destination) throws IOException;
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
            if (saveBlocked) {
                return false;
            }
            loadHoldingLock(file, nowMillis, expiryMillis, logger, secrets);
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
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            boolean preserved = quarantineUnreadable(file, secrets, logger, e);
            if (store != null && !preserved) {
                store.saveBlocked = true;
            }
            return null;
        }
        if (redactDocument(yaml, secrets) && rewrite(file, yaml, logger, secrets) && logger != null) {
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

    private static boolean rewrite(File file, YamlConfiguration yaml, Logger logger, Iterable<String> secrets) {
        File parent = file.getParentFile();
        File temporary = new File(parent == null ? new File(".") : parent,
                file.getName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }
            AtomicFiles.createPrivate(temporary.toPath());
            yaml.save(temporary);
            AtomicFiles.moveReplacing(temporary.toPath(), file.toPath());
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
            saveHoldingLock(file, logger, summaries, publish);
        } finally {
            diskLock.unlock();
        }
    }

    private void saveHoldingLock(File file, Logger logger, boolean summaries, Publish publish) {
        if (saveBlocked) {
            if (logger != null && !saveBlockedLogged) {
                saveBlockedLogged = true;
                logger.warning("Refusing to overwrite dialogue-memory.yml because it could not be read. "
                        + "Repair the broken file and restart.");
            }
            return;
        }
        YamlConfiguration yaml = document(summaries);
        File parent = file.getParentFile();
        try {
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
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
                    return;
                }
            }
            File temporary = new File(parent == null ? new File(".") : parent,
                    file.getName() + "." + UUID.randomUUID() + ".tmp");
            try {
                AtomicFiles.createPrivate(temporary.toPath());
                yaml.save(temporary);
                publish.publish(temporary, file);
            } finally {
                if (temporary.isFile() && !temporary.equals(file)) {
                    temporary.delete();
                }
            }
        } catch (IOException e) {
            if (logger != null) {
                LogRedaction.warning(logger, "Failed to save dialogue-memory.yml", e, secrets());
            }
        }
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
        AtomicFiles.moveReplacing(temporary.toPath(), target.toPath());
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
     *
     * @return {@code true} when the broken text is preserved aside and the live path is free
     */
    private static boolean quarantineUnreadable(File file, Iterable<String> secrets, Logger logger, Exception error) {
        String detail = SecretMask.redact(
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                secrets);
        try {
            byte[] raw = Files.readAllBytes(file.toPath());
            Preserved preserved = preserveBytes(raw, secrets);
            Path copy = corruptDestination(file.toPath());
            writeCorruptAtomically(copy, preserved.bytes());
            try {
                Files.delete(file.toPath());
            } catch (IOException deleteFailed) {
                Files.write(file.toPath(), preserved.bytes());
                AtomicFiles.restrictOwnerReadWrite(file.toPath());
                if (logger != null) {
                    logger.warning("dialogue-memory.yml was not loaded: " + detail
                            + ". It could not be moved aside, so it was masked in place as well as copied to "
                            + copy.getFileName()
                            + ". It will not be overwritten. Repair that file and restart.");
                }
                return false;
            }
            if (logger != null) {
                logger.warning("dialogue-memory.yml was not loaded: " + detail
                        + ". " + preserved.note() + " The broken file was saved as " + copy.getFileName()
                        + " with owner-only permissions. Repair that copy, replace dialogue-memory.yml with it, and restart."
                        + " A new dialogue-memory.yml will be written for new lines and will not replace the copy.");
            }
            return true;
        } catch (IOException io) {
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
}
