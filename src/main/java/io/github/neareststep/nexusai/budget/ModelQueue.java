package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.RateLimitHeaders;
import io.github.neareststep.nexusai.config.ConfigVersions;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ordered model queue with per-entry and per-provider daily counters.
 * Counters persist in {@code usage.yml} and reset at server-local midnight.
 */
public final class ModelQueue {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<Slot> slots;
    private final Map<String, AtomicInteger> providerCounts = new LinkedHashMap<>();
    private final int remainingThreshold;
    private final long errorCooldownMillis;
    private final long authCooldownMillis;
    private final File usageFile;
    private final LongSupplier clock;
    private final Supplier<LocalDate> today;
    private final ZoneId zone;
    private final Logger logger;
    private final Object ioLock = new Object();
    private LocalDate day;

    public ModelQueue(
            List<QueueEntryConfig> entries,
            int remainingThreshold,
            long errorCooldownMillis,
            long authCooldownMillis,
            File usageFile,
            Logger logger
    ) {
        this(
                entries,
                remainingThreshold,
                errorCooldownMillis,
                authCooldownMillis,
                usageFile,
                System::currentTimeMillis,
                LocalDate::now,
                ZoneId.systemDefault(),
                logger
        );
    }

    public ModelQueue(
            List<QueueEntryConfig> entries,
            int remainingThreshold,
            long errorCooldownMillis,
            long authCooldownMillis,
            File usageFile,
            LongSupplier clock,
            Supplier<LocalDate> today,
            ZoneId zone,
            Logger logger
    ) {
        this.remainingThreshold = Math.max(0, remainingThreshold);
        this.errorCooldownMillis = Math.max(0L, errorCooldownMillis);
        this.authCooldownMillis = Math.max(0L, authCooldownMillis);
        this.usageFile = usageFile;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.today = today == null ? LocalDate::now : today;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.logger = logger == null ? Logger.getLogger("nexusai.queue") : logger;
        this.day = this.today.get();
        List<Slot> loaded = new ArrayList<>();
        List<QueueEntryConfig> source = entries == null ? List.of() : entries;
        for (int i = 0; i < source.size(); i++) {
            QueueEntryConfig entry = source.get(i);
            loaded.add(new Slot(i, entry.provider(), entry.model(), entry.dailyRequestLimit()));
            providerCounts.putIfAbsent(entry.provider(), new AtomicInteger());
        }
        this.slots = List.copyOf(loaded);
        load();
    }

    public int size() {
        return slots.size();
    }

    public synchronized List<Choice> selectable(long nowMillis) {
        roll(nowMillis);
        List<Choice> ready = new ArrayList<>();
        for (Slot slot : slots) {
            if (isSelectable(slot, nowMillis)) {
                ready.add(slot.choice());
            }
        }
        return ready;
    }

    public synchronized Optional<Choice> select(long nowMillis) {
        List<Choice> ready = selectable(nowMillis);
        return ready.isEmpty() ? Optional.empty() : Optional.of(ready.getFirst());
    }

    /**
     * Counts one outbound request. {@code false} when the daily cap was already reached.
     */
    public synchronized boolean tryConsume(int index, long nowMillis) {
        roll(nowMillis);
        Slot slot = slot(index);
        if (slot == null) {
            return false;
        }
        if (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit) {
            holdUntilMidnight(slot, nowMillis);
            return false;
        }
        int count = slot.requests.incrementAndGet();
        providerCounts.computeIfAbsent(slot.provider, ignored -> new AtomicInteger()).incrementAndGet();
        warnIfNeeded(slot, count);
        if (slot.dailyLimit > 0 && count >= slot.dailyLimit) {
            holdUntilMidnight(slot, nowMillis);
        }
        save();
        return true;
    }

    public synchronized int requestsToday(int index) {
        roll(clock.getAsLong());
        Slot slot = slot(index);
        return slot == null ? 0 : slot.requests.get();
    }

    public synchronized int providerRequests(String provider) {
        roll(clock.getAsLong());
        AtomicInteger count = providerCounts.get(provider);
        return count == null ? 0 : count.get();
    }

    public synchronized void observe(int index, Map<String, List<String>> headers, long nowMillis) {
        Slot slot = slot(index);
        if (slot == null) {
            return;
        }
        RateLimitHeaders.Snapshot snapshot = RateLimitHeaders.parse(headers, nowMillis);
        if (snapshot.remainingRequests() != null) {
            slot.remainingRequests = snapshot.remainingRequests();
        }
        if (snapshot.remainingTokens() != null) {
            slot.remainingTokens = snapshot.remainingTokens();
        }
        if (snapshot.exhausted(remainingThreshold)) {
            Long until = RateLimitHeaders.resetForExhausted(headers, nowMillis, remainingThreshold);
            long deadline = until == null ? nowMillis + errorCooldownMillis : until;
            cooldown(index, deadline, Hold.HEADER);
        }
    }

    public synchronized void markFailure(int index, AiRequestException error, long nowMillis) {
        if (error == null) {
            cooldown(index, nowMillis + errorCooldownMillis, Hold.ERROR);
            return;
        }
        long base = error.kind() == AiErrorKind.BAD_KEY || error.kind() == AiErrorKind.QUOTA
                ? authCooldownMillis
                : errorCooldownMillis;
        long until = nowMillis + base;
        if (error.retryAfterSeconds() > 0L) {
            until = Math.max(until, nowMillis + error.retryAfterSeconds() * 1000L);
        }
        Long headerReset = RateLimitHeaders.resetForExhausted(error.headers(), nowMillis, remainingThreshold);
        if (headerReset != null) {
            until = Math.max(until, headerReset);
        }
        Hold hold = error.kind() == AiErrorKind.RATE_LIMIT ? Hold.HEADER : Hold.ERROR;
        cooldown(index, until, hold);
    }

    public synchronized void cooldown(int index, long untilMillis, Hold hold) {
        Slot slot = slot(index);
        if (slot == null) {
            return;
        }
        if (untilMillis > slot.unavailableUntil) {
            slot.unavailableUntil = untilMillis;
            slot.hold = hold == null ? Hold.ERROR : hold;
        }
        save();
    }

    public synchronized List<Status> status(long nowMillis) {
        roll(nowMillis);
        List<Status> lines = new ArrayList<>();
        boolean activeAssigned = false;
        for (Slot slot : slots) {
            String state;
            if (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit) {
                state = "LIMIT REACHED (" + slot.requests.get() + "/" + slot.dailyLimit + ")";
            } else if (nowMillis < slot.unavailableUntil) {
                String when = Instant.ofEpochMilli(slot.unavailableUntil).atZone(zone).format(CLOCK);
                state = "COOLDOWN until " + when;
            } else if (!activeAssigned) {
                state = "ACTIVE";
                activeAssigned = true;
            } else {
                state = "AVAILABLE";
            }
            lines.add(new Status(
                    slot.index,
                    slot.provider,
                    slot.model,
                    slot.requests.get(),
                    slot.dailyLimit,
                    slot.remainingRequests,
                    slot.remainingTokens,
                    state
            ));
        }
        return List.copyOf(lines);
    }

    public synchronized void save() {
        if (usageFile == null) {
            return;
        }
        synchronized (ioLock) {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("config-version", ConfigVersions.CURRENT);
                yaml.set("day", day.toString());
                for (Map.Entry<String, AtomicInteger> entry : providerCounts.entrySet()) {
                    yaml.set("providers." + entry.getKey(), entry.getValue().get());
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Slot slot : slots) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", slot.storageId());
                    row.put("provider", slot.provider);
                    row.put("model", slot.model);
                    row.put("requests", slot.requests.get());
                    rows.add(row);
                }
                yaml.set("entries", rows);
                File parent = usageFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                yaml.save(usageFile);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Failed to save usage counters", e);
            }
        }
    }

    private void load() {
        if (usageFile == null || !usageFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(usageFile);
        String storedDay = yaml.getString("day", "");
        if (storedDay == null || !storedDay.equals(day.toString())) {
            return;
        }
        if (yaml.isConfigurationSection("providers")) {
            for (String provider : yaml.getConfigurationSection("providers").getKeys(false)) {
                providerCounts.computeIfAbsent(provider, ignored -> new AtomicInteger())
                        .set(Math.max(0, yaml.getInt("providers." + provider, 0)));
            }
        }
        for (Map<?, ?> row : yaml.getMapList("entries")) {
            Object id = row.get("id");
            if (id == null) {
                continue;
            }
            int requests = 0;
            Object raw = row.get("requests");
            if (raw instanceof Number number) {
                requests = Math.max(0, number.intValue());
            }
            for (Slot slot : slots) {
                if (slot.storageId().equals(String.valueOf(id))) {
                    slot.requests.set(requests);
                    if (slot.dailyLimit > 0 && requests >= slot.dailyLimit) {
                        holdUntilMidnight(slot, clock.getAsLong());
                    }
                    if (slot.dailyLimit > 0 && requests * 100L >= slot.dailyLimit * 80L) {
                        slot.warned = true;
                    }
                }
            }
        }
    }

    private synchronized void roll(long nowMillis) {
        LocalDate current = today.get();
        if (current.equals(day)) {
            return;
        }
        day = current;
        for (Slot slot : slots) {
            slot.requests.set(0);
            slot.warned = false;
            slot.remainingRequests = null;
            slot.remainingTokens = null;
            if (slot.hold == Hold.DAILY) {
                slot.unavailableUntil = 0L;
                slot.hold = Hold.NONE;
            }
        }
        for (AtomicInteger count : providerCounts.values()) {
            count.set(0);
        }
        save();
    }

    private boolean isSelectable(Slot slot, long nowMillis) {
        if (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit) {
            return false;
        }
        return nowMillis >= slot.unavailableUntil;
    }

    private void holdUntilMidnight(Slot slot, long nowMillis) {
        long midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if (midnight <= nowMillis) {
            midnight = nowMillis + 1_000L;
        }
        if (midnight > slot.unavailableUntil || slot.hold != Hold.DAILY) {
            slot.unavailableUntil = midnight;
            slot.hold = Hold.DAILY;
        }
    }

    private void warnIfNeeded(Slot slot, int count) {
        if (slot.dailyLimit <= 0 || slot.warned) {
            return;
        }
        if (count * 100L < slot.dailyLimit * 80L) {
            return;
        }
        slot.warned = true;
        logger.warning("Model queue entry " + slot.provider + " / " + slot.model
                + " has used " + count + "/" + slot.dailyLimit
                + " requests today (80% of the daily limit).");
    }

    private Slot slot(int index) {
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        return slots.get(index);
    }

    public enum Hold {
        NONE, DAILY, HEADER, ERROR
    }

    public record Choice(int index, String provider, String model) {
    }

    public record Status(
            int index,
            String provider,
            String model,
            int requestsToday,
            int dailyLimit,
            Long remainingRequests,
            Long remainingTokens,
            String state
    ) {
    }

    private static final class Slot {
        private final int index;
        private final String provider;
        private final String model;
        private final int dailyLimit;
        private final AtomicInteger requests = new AtomicInteger();
        private volatile long unavailableUntil;
        private volatile Hold hold = Hold.NONE;
        private volatile Long remainingRequests;
        private volatile Long remainingTokens;
        private volatile boolean warned;

        private Slot(int index, String provider, String model, int dailyLimit) {
            this.index = index;
            this.provider = provider;
            this.model = model;
            this.dailyLimit = dailyLimit;
        }

        private Choice choice() {
            return new Choice(index, provider, model);
        }

        private String storageId() {
            return index + "|" + provider + "|" + model;
        }
    }
}
