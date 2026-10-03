package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.ConfigVersions;
import io.github.neareststep.nexusai.config.FileBackup;
import io.github.neareststep.nexusai.config.YamlStrings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes the answer pool to {@code pool.yml} immediately on flush and, while running, after a quiet period.
 */
public final class PoolStore {

    private final File file;
    private final ScheduledExecutorService scheduler;
    private final long delayMillis;
    private final Logger logger;
    private final boolean enabled;
    private final boolean allowMarkup;
    private final Object scheduleLock = new Object();
    private final Object ioLock = new Object();
    private ScheduledFuture<?> pending;
    /** Set when pool.yml cannot be parsed, so a later save does not destroy the original bytes. */
    private volatile boolean refuseOverwrite;
    /** The invalid-file warning is logged once until a later read succeeds. */
    private volatile boolean invalidNoted;

    public PoolStore(File file, ScheduledExecutorService scheduler, Duration delay, Logger logger, boolean enabled) {
        this(file, scheduler, delay, logger, enabled, false);
    }

    public PoolStore(
            File file,
            ScheduledExecutorService scheduler,
            Duration delay,
            Logger logger,
            boolean enabled,
            boolean allowMarkup
    ) {
        this.file = file;
        this.scheduler = scheduler;
        this.delayMillis = delay == null ? 2_000L : Math.max(1L, delay.toMillis());
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled && file != null && scheduler != null;
        this.allowMarkup = allowMarkup;
    }

    public static PoolStore disabled() {
        return new PoolStore(null, null, Duration.ofSeconds(2), Logger.getLogger("nexusai.pool.disabled"), false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void load(AiPool pool, Map<String, Integer> limits) {
        load(pool, limits, ignored -> null);
    }

    /**
     * @param dynamicLimits size for a saved prompt that is not in {@code limits}, or {@code null} to skip it
     */
    public void load(AiPool pool, Map<String, Integer> limits, Function<String, Integer> dynamicLimits) {
        Objects.requireNonNull(pool, "pool");
        if (!enabled || file == null || !file.isFile()) {
            return;
        }
        YamlConfiguration yaml = readYaml();
        if (yaml == null) {
            return;
        }
        try {
            List<Map<?, ?>> rows = yaml.getMapList("pools");
            for (Map<?, ?> row : rows) {
                Object promptValue = row.get("prompt");
                if (promptValue == null) {
                    continue;
                }
                String prompt = String.valueOf(promptValue);
                Object formatValue = row.get("format");
                String format = formatValue == null ? null : String.valueOf(formatValue);
                String memory = PoolKeys.memory(format, prompt);
                Integer limit = limits.get(memory);
                if (limit == null && dynamicLimits != null) {
                    limit = dynamicLimits.apply(memory);
                }
                if (limit == null) {
                    continue;
                }
                List<String> answers = readAnswers(row.get("answers"), allowMarkup);
                if (answers.size() > limit) {
                    answers = new ArrayList<>(answers.subList(0, limit));
                }
                pool.replace(memory, answers);
            }
        } catch (RuntimeException e) {
            noteUnreadable(e);
        }
    }

    public void markDirty(AiPool pool, Map<String, Integer> limits) {
        if (!enabled) {
            return;
        }
        Map<String, Integer> snapshot = Map.copyOf(limits);
        synchronized (scheduleLock) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = scheduler.schedule(() -> saveNow(pool, snapshot), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void flush(AiPool pool, Map<String, Integer> limits) {
        if (!enabled) {
            return;
        }
        synchronized (scheduleLock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }
        saveNow(pool, limits);
    }

    public List<String> savedPrompts() {
        if (!enabled || file == null || !file.isFile()) {
            return List.of();
        }
        YamlConfiguration yaml = readYaml();
        if (yaml == null) {
            return List.of();
        }
        try {
            List<Map<?, ?>> rows = yaml.getMapList("pools");
            List<String> prompts = new ArrayList<>();
            for (Map<?, ?> row : rows) {
                Object promptValue = row.get("prompt");
                if (promptValue != null) {
                    prompts.add(String.valueOf(promptValue));
                }
            }
            return prompts;
        } catch (RuntimeException e) {
            noteUnreadable(e);
            return List.of();
        }
    }

    /**
     * Parses {@code pool.yml} without {@link YamlConfiguration#loadConfiguration(File)}, which logs a
     * severe stack trace on invalid YAML. A parse failure is one warning and leaves the file alone.
     */
    private YamlConfiguration readYaml() {
        if (!enabled || file == null || !file.isFile()) {
            return null;
        }
        try {
            String raw = Files.readString(file.toPath());
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(raw);
            } catch (InvalidConfigurationException e) {
                noteUnreadable(e);
                return null;
            }
            refuseOverwrite = false;
            invalidNoted = false;
            return yaml;
        } catch (IOException | RuntimeException e) {
            noteUnreadable(e);
            return null;
        }
    }

    private void noteUnreadable(Exception error) {
        refuseOverwrite = true;
        logger.log(Level.FINE, "Answer pool file could not be parsed: " + file.getAbsolutePath(), error);
        if (invalidNoted) {
            return;
        }
        invalidNoted = true;
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        detail = detail.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
        logger.warning("pool.yml at " + file.getAbsolutePath()
                + " could not be parsed (" + detail
                + "). The file was left untouched and the answer pool is empty until the file is fixed and reloaded.");
    }

    public void saveNow(AiPool pool, Map<String, Integer> limits) {
        if (!enabled || file == null) {
            return;
        }
        synchronized (ioLock) {
            if (refuseOverwrite) {
                return;
            }
            try {
                Set<PosixFilePermission> permissions = readPermissions(file);
                if (file.isFile() && containsUncleanAnswers(file)) {
                    Path backup = FileBackup.backup(file.toPath());
                    applyPermissions(backup, permissions);
                    logger.info("Backed up pool.yml to " + backup.toAbsolutePath());
                }
                StringBuilder yaml = new StringBuilder();
                yaml.append("config-version: ").append(ConfigVersions.CURRENT).append('\n');
                yaml.append("pools:\n");
                boolean any = false;
                for (Map.Entry<String, Integer> entry : limits.entrySet()) {
                    List<String> answers = pool.copy(entry.getKey());
                    int limit = Math.max(0, entry.getValue());
                    if (answers.size() > limit) {
                        answers = new ArrayList<>(answers.subList(0, limit));
                    }
                    if (answers.isEmpty()) {
                        continue;
                    }
                    any = true;
                    PoolKeys.Parsed parsed = PoolKeys.parse(entry.getKey());
                    yaml.append("  - prompt: ").append(YamlStrings.quote(parsed.prompt())).append('\n');
                    if (!io.github.neareststep.nexusai.config.FormatPresets.SIMPLE.equals(parsed.format())) {
                        yaml.append("    format: ").append(YamlStrings.quote(parsed.format())).append('\n');
                    }
                    yaml.append("    answers:\n");
                    for (String answer : answers) {
                        yaml.append("      - ").append(YamlStrings.quote(answer)).append('\n');
                    }
                }
                if (!any) {
                    yaml.setLength(0);
                    yaml.append("config-version: ").append(ConfigVersions.CURRENT).append('\n');
                    yaml.append("pools: []\n");
                }
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                File temporary = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
                java.nio.file.Files.writeString(temporary.toPath(), yaml.toString(), java.nio.charset.StandardCharsets.UTF_8);
                applyPermissions(temporary.toPath(), permissions);
                moveIntoPlace(temporary);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to save answer pool to " + file.getName(), e);
            }
        }
    }

    /**
     * True when a stored answer would change or disappear under {@link PlayerInput#stripSectionSigns}.
     * A later save of already-clean text does not take another backup.
     */
    private boolean containsUncleanAnswers(File source) throws IOException, InvalidConfigurationException {
        String raw = Files.readString(source.toPath());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(raw);
        for (Map<?, ?> row : yaml.getMapList("pools")) {
            Object answers = row.get("answers");
            if (!(answers instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item);
                String cleaned = PlayerInput.stripSectionSigns(text, allowMarkup).trim();
                if (!cleaned.equals(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<PosixFilePermission> readPermissions(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        try {
            return Files.getPosixFilePermissions(source.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
    }

    private static void applyPermissions(Path target, Set<PosixFilePermission> permissions) {
        if (target == null || permissions == null) {
            return;
        }
        try {
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // The write still replaces the bytes. A non-POSIX volume has no mode to copy.
        }
    }

    private void moveIntoPlace(File temporary) throws IOException {
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> readAnswers(Object raw, boolean allowMarkup) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> answers = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = PlayerInput.stripSectionSigns(String.valueOf(item), allowMarkup).trim();
            if (!text.isEmpty()) {
                answers.add(text);
            }
        }
        return answers;
    }
}
