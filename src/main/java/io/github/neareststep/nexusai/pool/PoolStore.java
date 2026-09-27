package io.github.neareststep.nexusai.pool;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
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
        Objects.requireNonNull(pool, "pool");
        if (!enabled || file == null || !file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<Map<?, ?>> rows = yaml.getMapList("pools");
            for (Map<?, ?> row : rows) {
                Object promptValue = row.get("prompt");
                if (promptValue == null) {
                    continue;
                }
                String prompt = String.valueOf(promptValue);
                Integer limit = limits.get(prompt);
                if (limit == null) {
                    continue;
                }
                List<String> answers = readAnswers(row.get("answers"));
                if (answers.size() > limit) {
                    answers = new ArrayList<>(answers.subList(0, limit));
                }
                pool.replace(prompt, answers);
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Failed to load answer pool from " + file.getName(), e);
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

    public void saveNow(AiPool pool, Map<String, Integer> limits) {
        if (!enabled || file == null) {
            return;
        }
        synchronized (ioLock) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : limits.entrySet()) {
                    List<String> answers = pool.copy(entry.getKey());
                    int limit = Math.max(0, entry.getValue());
                    if (answers.size() > limit) {
                        answers = new ArrayList<>(answers.subList(0, limit));
                    }
                    if (answers.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("prompt", entry.getKey());
                    row.put("answers", answers);
                    rows.add(row);
                }
                yaml.set("pools", rows);
                File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                File temporary = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
                yaml.save(temporary);
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
