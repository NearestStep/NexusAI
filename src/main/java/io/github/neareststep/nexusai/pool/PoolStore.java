package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.config.ConfigVersions;
import io.github.neareststep.nexusai.config.YamlStrings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private final Object scheduleLock = new Object();
    private final Object ioLock = new Object();
    private ScheduledFuture<?> pending;
    /** Set when pool.yml cannot be parsed, so a later save does not destroy the original bytes. */
    private volatile boolean refuseOverwrite;

    public PoolStore(File file, ScheduledExecutorService scheduler, Duration delay, Logger logger, boolean enabled) {
        this.file = file;
        this.scheduler = scheduler;
        this.delayMillis = delay == null ? 2_000L : Math.max(1L, delay.toMillis());
        this.logger = Objects.requireNonNull(logger, "logger");
        this.enabled = enabled && file != null && scheduler != null;
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
        try {
            String raw = Files.readString(file.toPath());
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(raw);
            } catch (InvalidConfigurationException e) {
                refuseOverwrite = true;
                logger.log(Level.WARNING, "pool.yml is not valid YAML. It was left unchanged and will not be overwritten.", e);
                return;
            }
            refuseOverwrite = false;
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
                List<String> answers = readAnswers(row.get("answers"));
                if (answers.size() > limit) {
                    answers = new ArrayList<>(answers.subList(0, limit));
                }
                pool.replace(memory, answers);
            }
        } catch (IOException | RuntimeException e) {
            refuseOverwrite = true;
            logger.log(Level.WARNING, "Failed to load answer pool from " + file.getName()
                    + ". The file will not be overwritten.", e);
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
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
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
            logger.log(Level.WARNING, "Failed to read answer pool from " + file.getName(), e);
            return List.of();
        }
    }

    public void saveNow(AiPool pool, Map<String, Integer> limits) {
        if (!enabled || file == null) {
            return;
        }
        synchronized (ioLock) {
            if (refuseOverwrite) {
                logger.warning("Refusing to overwrite invalid pool.yml. Fix or replace the file, then reload.");
                return;
            }
            try {
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
                moveIntoPlace(temporary);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to save answer pool to " + file.getName(), e);
            }
        }
    }

    private void moveIntoPlace(File temporary) throws IOException {
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> readAnswers(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> answers = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item);
            if (!text.isBlank()) {
                answers.add(text);
            }
        }
        return answers;
    }
}
