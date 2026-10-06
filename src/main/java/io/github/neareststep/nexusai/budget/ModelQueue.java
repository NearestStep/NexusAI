package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.RateLimitHeaders;
import io.github.neareststep.nexusai.config.AtomicFiles;
import io.github.neareststep.nexusai.config.ConfigVersions;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.config.QueueStrategy;
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
    private final Map<String, Slot> fallbackSlots = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> providerCounts = new LinkedHashMap<>();
    private final int remainingThreshold;
    private final long errorCooldownMillis;
    private final long authCooldownMillis;
    private final File usageFile;
    private final LongSupplier clock;
    private final Supplier<LocalDate> today;
    private final ZoneId zone;
    private final Logger logger;
    private final QueueStrategy strategy;
    /** Next starting row for {@link QueueStrategy#ROUND_ROBIN}. Not used for failover. */
    private final AtomicInteger roundRobinCursor = new AtomicInteger();
    /**
     * Next starting row for an unpinned dialogue summary. Player replies and placeholders
     * keep {@link #roundRobinCursor}.
     */
    private final AtomicInteger summaryRoundRobinCursor = new AtomicInteger();
    private final Object ioLock = new Object();
    private LocalDate day;
    private int moderationChecks;
    private int moderationFlags;

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
                logger,
                QueueStrategy.FAILOVER
        );
    }

    public ModelQueue(
            List<QueueEntryConfig> entries,
            int remainingThreshold,
            long errorCooldownMillis,
            long authCooldownMillis,
            File usageFile,
            Logger logger,
            QueueStrategy strategy
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
                logger,
                strategy
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
        this(
                entries,
                remainingThreshold,
                errorCooldownMillis,
                authCooldownMillis,
                usageFile,
                clock,
                today,
                zone,
                logger,
                QueueStrategy.FAILOVER
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
            Logger logger,
            QueueStrategy strategy
    ) {
        this.remainingThreshold = Math.max(0, remainingThreshold);
        this.errorCooldownMillis = Math.max(0L, errorCooldownMillis);
        this.authCooldownMillis = Math.max(0L, authCooldownMillis);
        this.usageFile = usageFile;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.today = today == null ? LocalDate::now : today;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.logger = logger == null ? Logger.getLogger("nexusai.queue") : logger;
        this.strategy = strategy == null ? QueueStrategy.FAILOVER : strategy;
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
        return selectable(nowMillis, false);
    }

    /**
     * @param ignoreCooldown when true, entries in a temporary error or header cooldown are included.
     *                        A daily cap is never ignored.
     */
    public synchronized List<Choice> selectable(long nowMillis, boolean ignoreCooldown) {
        return selectFrom(nowMillis, ignoreCooldown, roundRobinCursor);
    }

    /**
     * Same walk as {@link #selectable(long, boolean)} for an unpinned dialogue summary.
     * Round-robin advances {@link #summaryRoundRobinCursor} and leaves the player-reply cursor
     * where it is. Failover does not use either cursor. A pinned summary must not call this.
     */
    public synchronized List<Choice> selectableSummary(long nowMillis) {
        return selectFrom(nowMillis, false, summaryRoundRobinCursor);
    }

    private List<Choice> selectFrom(long nowMillis, boolean ignoreCooldown, AtomicInteger cursor) {
        roll(nowMillis);
        if (strategy != QueueStrategy.ROUND_ROBIN || slots.isEmpty()) {
            return readyInOrder(nowMillis, ignoreCooldown);
        }
        int start = nextIndex(nowMillis, ignoreCooldown, cursor);
        if (start < 0) {
            return List.of();
        }
        // The next request of this cursor starts at the following row.
        cursor.set(Math.floorMod(start + 1, slots.size()));
        List<Choice> ready = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(Math.floorMod(start + i, slots.size()));
            if (isSelectable(slot, nowMillis, ignoreCooldown)) {
                ready.add(slot.choice());
            }
        }
        return ready;
    }

    public QueueStrategy strategy() {
        return strategy;
    }

    /**
     * The row the next request will start from. Does not advance the round-robin cursor.
     * Failover and round-robin both report the next selectable row. A cooled, exhausted, or
     * threshold-blocked row is skipped. Empty when nothing is selectable.
     */
    public synchronized Optional<Choice> nextStart(long nowMillis) {
        roll(nowMillis);
        if (slots.isEmpty()) {
            return Optional.empty();
        }
        if (strategy != QueueStrategy.ROUND_ROBIN) {
            for (Slot slot : slots) {
                if (isSelectable(slot, nowMillis, false)) {
                    return Optional.of(slot.choice());
                }
            }
            return Optional.empty();
        }
        int index = nextRoundRobinIndex(nowMillis, false);
        if (index < 0) {
            return Optional.empty();
        }
        return Optional.of(slots.get(index).choice());
    }

    /**
     * First selectable row at or after the round-robin cursor. Does not move the cursor.
     */
    private int nextRoundRobinIndex(long nowMillis, boolean ignoreCooldown) {
        return nextIndex(nowMillis, ignoreCooldown, roundRobinCursor);
    }

    private int nextIndex(long nowMillis, boolean ignoreCooldown, AtomicInteger cursor) {
        int size = slots.size();
        if (size == 0) {
            return -1;
        }
        int at = Math.floorMod(cursor.get(), size);
        for (int i = 0; i < size; i++) {
            int index = Math.floorMod(at + i, size);
            if (isSelectable(slots.get(index), nowMillis, ignoreCooldown)) {
                return index;
            }
        }
        return -1;
    }

    private List<Choice> readyInOrder(long nowMillis, boolean ignoreCooldown) {
        List<Choice> ready = new ArrayList<>();
        for (Slot slot : slots) {
            if (isSelectable(slot, nowMillis, ignoreCooldown)) {
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
        return consume(slot(index), nowMillis);
    }

    /**
     * How a configured fallback model may be called.
     * A queue row with the same provider and model is reused, so its daily cap and cooldown apply
     * and it is not called twice in one request. A model that is not in the queue has its own cooldown.
     * It is skipped when every queue row of that provider is already at its daily cap.
     */
    public synchronized FallbackPlan planFallback(
            String provider,
            String model,
            long nowMillis,
            boolean ignoreCooldown,
            java.util.Set<Integer> attempted
    ) {
        roll(nowMillis);
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            return FallbackPlan.blocked();
        }
        java.util.Set<Integer> tried = attempted == null ? java.util.Set.of() : attempted;
        for (Slot slot : slots) {
            if (slot.provider.equals(provider) && slot.model.equals(model)) {
                if (tried.contains(slot.index) || !isSelectable(slot, nowMillis, ignoreCooldown)) {
                    return FallbackPlan.blocked();
                }
                return FallbackPlan.queue(slot.index, provider, model);
            }
        }
        if (providerDailyBlocked(provider)) {
            return FallbackPlan.blocked();
        }
        Slot dedicated = fallbackSlot(provider, model);
        if (!isSelectable(dedicated, nowMillis, ignoreCooldown)) {
            return FallbackPlan.blocked();
        }
        return FallbackPlan.dedicated(provider, model);
    }

    public synchronized boolean tryConsumeFallback(String provider, String model, long nowMillis) {
        roll(nowMillis);
        if (provider == null || model == null) {
            return false;
        }
        return consume(fallbackSlot(provider, model), nowMillis);
    }

    private boolean consume(Slot slot, long nowMillis) {
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
        observeSlot(slot(index), headers, nowMillis);
    }

    public synchronized void observeFallback(String provider, String model, Map<String, List<String>> headers, long nowMillis) {
        if (provider == null || model == null) {
            return;
        }
        observeSlot(fallbackSlot(provider, model), headers, nowMillis);
    }

    private void observeSlot(Slot slot, Map<String, List<String>> headers, long nowMillis) {
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
            long previous = slot.unavailableUntil;
            cool(slot, deadline, Hold.HEADER);
            if (slot.unavailableUntil != previous && slot.hold == Hold.HEADER) {
                slot.lastError = null;
            }
        }
    }

    /**
     * Why {@link #selectable(long)} is empty. The kind and text are the last provider failure
     * when one was recorded. Daily exhaustion stays a local limit. Every message names the
     * soonest time a retry is possible.
     * <p>
     * When one entry is blocked by a daily request limit or by a remaining request or token budget,
     * and another entry failed with a different provider error, the text names each entry's own
     * reason. A stored HTTP status is not used as the reason for the budget block.
     */
    public synchronized AiRequestException explain(AiRequestException last, long nowMillis) {
        roll(nowMillis);
        long soonest = Long.MAX_VALUE;
        AiRequestException stored = null;
        long storedAt = Long.MIN_VALUE;
        boolean sawBlocked = false;
        boolean dailyOnly = true;
        boolean sawHeader = false;
        List<Slot> blocked = new ArrayList<>();
        for (Slot slot : slots) {
            if (isSelectable(slot, nowMillis, false)) {
                continue;
            }
            sawBlocked = true;
            blocked.add(slot);
            if (slot.unavailableUntil > nowMillis) {
                soonest = Math.min(soonest, slot.unavailableUntil);
            }
            boolean daily = isDaily(slot);
            if (!daily) {
                dailyOnly = false;
            }
            if (slot.hold == Hold.HEADER) {
                sawHeader = true;
            }
            if (slot.lastError != null && slot.lastFailedAt >= storedAt) {
                stored = slot.lastError;
                storedAt = slot.lastFailedAt;
            }
        }
        List<Slot> fallbackBudgets = blockedFallbackBudgets(nowMillis);
        AiRequestException cause = last != null ? last : stored;
        if (namesDistinctReasons(blocked, fallbackBudgets)) {
            for (Slot slot : fallbackBudgets) {
                if (slot.unavailableUntil > nowMillis) {
                    soonest = Math.min(soonest, slot.unavailableUntil);
                }
            }
            String retry = soonest == Long.MAX_VALUE ? "" : " Retry after " + formatTime(soonest) + ".";
            return namedReasons(blocked, fallbackBudgets, cause, retry);
        }
        String retry = soonest == Long.MAX_VALUE ? "" : " Retry after " + formatTime(soonest) + ".";
        if (cause != null && !(last == null && dailyOnly)) {
            return withRetry(cause, retry);
        }
        if (sawBlocked && dailyOnly) {
            return new AiRequestException(
                    AiErrorKind.LOCAL_LIMIT, 0, "All model-queue entries are exhausted." + retry, null);
        }
        if (sawHeader) {
            return new AiRequestException(AiErrorKind.RATE_LIMIT, 0, "AI provider rate limit." + retry, null);
        }
        return new AiRequestException(AiErrorKind.OTHER, 0, "Model queue entry is cooling down." + retry, null);
    }

    /**
     * True when every queue row for {@code provider} is held by a rate-limit, quota, or auth failure.
     * A daily cap and a generic error cooldown are not this hold. A provider with no row is false.
     */
    public synchronized boolean providerPauseHold(String provider, long nowMillis) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        boolean saw = false;
        for (Slot slot : slots) {
            if (!slot.provider.equals(provider)) {
                continue;
            }
            saw = true;
            if (nowMillis >= slot.unavailableUntil) {
                return false;
            }
            boolean pausing = slot.hold == Hold.HEADER
                    || (slot.lastError != null && slot.lastError.kind().pausesProvider());
            if (!pausing) {
                return false;
            }
        }
        return saw;
    }

    public synchronized void markFailure(int index, AiRequestException error, long nowMillis) {
        markSlotFailure(slot(index), error, nowMillis);
    }

    public synchronized void markFallbackFailure(String provider, String model, AiRequestException error, long nowMillis) {
        if (provider == null || model == null) {
            return;
        }
        markSlotFailure(fallbackSlot(provider, model), error, nowMillis);
    }

    private void markSlotFailure(Slot failed, AiRequestException error, long nowMillis) {
        if (failed != null) {
            failed.lastError = error;
            failed.lastFailedAt = nowMillis;
        }
        if (failed == null) {
            return;
        }
        if (error == null) {
            cool(failed, nowMillis + errorCooldownMillis, Hold.ERROR);
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
        cool(failed, until, hold);
    }

    /**
     * Counts one discarded answer. Does not cool the row down and does not record a provider error.
     */
    public synchronized int moderationChecks() {
        roll(clock.getAsLong());
        return moderationChecks;
    }

    public synchronized int moderationFlags() {
        roll(clock.getAsLong());
        return moderationFlags;
    }

    public synchronized void recordModerationCheck() {
        roll(clock.getAsLong());
        moderationChecks++;
        save();
    }

    public synchronized void recordModerationFlag() {
        roll(clock.getAsLong());
        moderationFlags++;
        save();
    }

    public synchronized void recordRejection(int index) {
        reject(slot(index));
    }

    public synchronized void recordFallbackRejection(String provider, String model) {
        if (provider == null || model == null) {
            return;
        }
        reject(fallbackSlot(provider, model));
    }

    private void reject(Slot slot) {
        if (slot == null) {
            return;
        }
        slot.rejected.incrementAndGet();
        save();
    }

    public synchronized void cooldown(int index, long untilMillis, Hold hold) {
        cool(slot(index), untilMillis, hold);
    }

    public synchronized void cooldownFallback(String provider, String model, long untilMillis, Hold hold) {
        if (provider == null || model == null) {
            return;
        }
        cool(fallbackSlot(provider, model), untilMillis, hold);
    }

    private void cool(Slot slot, long untilMillis, Hold hold) {
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
                    slot.rejected.get(),
                    state
            ));
        }
        return List.copyOf(lines);
    }

    /**
     * Status line for the configured fallback model. A matching queue row shares that row's counters.
     * Selectable fallback models are {@code READY}, never {@code ACTIVE}.
     */
    public synchronized Status fallbackStatus(String provider, String model, long nowMillis) {
        roll(nowMillis);
        Slot slot = findQueueSlot(provider, model);
        if (slot == null && provider != null && model != null && !provider.isBlank() && !model.isBlank()) {
            slot = fallbackSlot(provider, model);
        }
        if (slot == null) {
            return new Status(-1, provider == null ? "" : provider, model == null ? "" : model,
                    0, 0, null, null, 0, "NOT SET");
        }
        String state = describe(slot, nowMillis, false);
        return new Status(
                slot.index,
                slot.provider,
                slot.model,
                slot.requests.get(),
                slot.dailyLimit,
                slot.remainingRequests,
                slot.remainingTokens,
                slot.rejected.get(),
                state
        );
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
                    row.put("rejected", slot.rejected.get());
                    rows.add(row);
                }
                yaml.set("entries", rows);
                yaml.set("moderation.checks", moderationChecks);
                yaml.set("moderation.flags", moderationFlags);
                if (!fallbackSlots.isEmpty()) {
                    List<Map<String, Object>> fallbackRows = new ArrayList<>();
                    for (Slot slot : fallbackSlots.values()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", slot.storageId());
                        row.put("provider", slot.provider);
                        row.put("model", slot.model);
                        row.put("requests", slot.requests.get());
                        row.put("rejected", slot.rejected.get());
                        fallbackRows.add(row);
                    }
                    yaml.set("fallback", fallbackRows);
                }
                File parent = usageFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                if (!usageFile.isFile()) {
                    AtomicFiles.createPrivate(usageFile.toPath());
                }
                AtomicFiles.preserving(usageFile.toPath(), () -> yaml.save(usageFile));
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
            int rejected = 0;
            Object rawRejected = row.get("rejected");
            if (rawRejected instanceof Number number) {
                rejected = Math.max(0, number.intValue());
            }
            for (Slot slot : slots) {
                if (slot.storageId().equals(String.valueOf(id))) {
                    slot.requests.set(requests);
                    slot.rejected.set(rejected);
                    if (slot.dailyLimit > 0 && requests >= slot.dailyLimit) {
                        holdUntilMidnight(slot, clock.getAsLong());
                    }
                    if (slot.dailyLimit > 0 && requests * 100L >= slot.dailyLimit * 80L) {
                        slot.warned = true;
                    }
                }
            }
        }
        this.moderationChecks = Math.max(0, yaml.getInt("moderation.checks", 0));
        this.moderationFlags = Math.max(0, yaml.getInt("moderation.flags", 0));
        for (Map<?, ?> row : yaml.getMapList("fallback")) {
            Object provider = row.get("provider");
            Object model = row.get("model");
            if (provider == null || model == null) {
                continue;
            }
            int requests = 0;
            Object raw = row.get("requests");
            if (raw instanceof Number number) {
                requests = Math.max(0, number.intValue());
            }
            int rejected = 0;
            Object rawRejected = row.get("rejected");
            if (rawRejected instanceof Number number) {
                rejected = Math.max(0, number.intValue());
            }
            Slot slot = fallbackSlot(String.valueOf(provider), String.valueOf(model));
            slot.requests.set(requests);
            slot.rejected.set(rejected);
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
            slot.rejected.set(0);
        }
        for (Slot slot : fallbackSlots.values()) {
            slot.requests.set(0);
            slot.warned = false;
            slot.remainingRequests = null;
            slot.remainingTokens = null;
            if (slot.hold == Hold.DAILY) {
                slot.unavailableUntil = 0L;
                slot.hold = Hold.NONE;
            }
            slot.rejected.set(0);
        }
        for (AtomicInteger count : providerCounts.values()) {
            count.set(0);
        }
        moderationChecks = 0;
        moderationFlags = 0;
        save();
    }

    private boolean isSelectable(Slot slot, long nowMillis, boolean ignoreCooldown) {
        if (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit) {
            return false;
        }
        if (ignoreCooldown && slot.hold != Hold.DAILY) {
            return true;
        }
        return nowMillis >= slot.unavailableUntil;
    }

    private String formatTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).format(CLOCK);
    }

    private boolean namesDistinctReasons(List<Slot> blocked, List<Slot> fallbackBudgets) {
        boolean budget = !fallbackBudgets.isEmpty();
        boolean other = false;
        for (Slot slot : blocked) {
            if (isBudgetBlock(slot)) {
                budget = true;
            } else {
                other = true;
            }
        }
        return budget && other;
    }

    private List<Slot> blockedFallbackBudgets(long nowMillis) {
        List<Slot> extra = new ArrayList<>();
        for (Slot slot : fallbackSlots.values()) {
            if (findQueueSlot(slot.provider, slot.model) != null) {
                continue;
            }
            if (isSelectable(slot, nowMillis, false) || !isBudgetBlock(slot)) {
                continue;
            }
            extra.add(slot);
        }
        return extra;
    }

    private AiRequestException namedReasons(
            List<Slot> blocked,
            List<Slot> fallbackBudgets,
            AiRequestException cause,
            String retry
    ) {
        List<String> parts = new ArrayList<>();
        for (Slot slot : blocked) {
            parts.add(slot.provider + " / " + slot.model + ": " + slotReason(slot));
        }
        for (Slot slot : fallbackBudgets) {
            parts.add(slot.provider + " / " + slot.model + ": " + slotReason(slot));
        }
        String message = String.join("; ", parts);
        if (cause == null) {
            return new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, message + retry, null);
        }
        AiRequestException named = new AiRequestException(
                cause.kind(),
                cause.status(),
                message,
                cause,
                cause.retryAfterSeconds(),
                cause.headers());
        for (String provider : AiRequestException.pausedProvidersOf(cause)) {
            named = named.withPausedProvider(provider);
        }
        return withRetry(named, retry);
    }

    private String slotReason(Slot slot) {
        if (isDaily(slot)) {
            if (slot.dailyLimit > 0) {
                return "daily request limit reached (" + slot.requests.get() + "/" + slot.dailyLimit + ")";
            }
            return "daily request limit reached";
        }
        if (slot.hold == Hold.HEADER && slot.lastError == null) {
            return headerBudgetReason(slot);
        }
        if (slot.lastError != null && slot.lastError.getMessage() != null && !slot.lastError.getMessage().isBlank()) {
            return withoutRetry(slot.lastError.getMessage());
        }
        if (slot.hold == Hold.HEADER) {
            return "AI provider rate limit";
        }
        return "cooling down";
    }

    private String headerBudgetReason(Slot slot) {
        boolean tokens = slot.remainingTokens != null && slot.remainingTokens <= remainingThreshold;
        boolean requests = slot.remainingRequests != null && slot.remainingRequests <= remainingThreshold;
        if (tokens && requests) {
            return "request and token budget exhausted";
        }
        if (tokens) {
            return "token budget exhausted";
        }
        if (requests) {
            return "request budget exhausted";
        }
        return "request or token budget exhausted";
    }

    private static boolean isDaily(Slot slot) {
        return slot.hold == Hold.DAILY
                || (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit);
    }

    private boolean isBudgetBlock(Slot slot) {
        if (isDaily(slot)) {
            return true;
        }
        return slot.hold == Hold.HEADER && slot.lastError == null;
    }

    private static String withoutRetry(String message) {
        String text = message == null ? "" : message.strip();
        int at = text.indexOf(" Retry after ");
        if (at >= 0) {
            return text.substring(0, at).strip();
        }
        return text;
    }

    private static AiRequestException withRetry(AiRequestException cause, String retry) {
        String message = cause.getMessage() == null ? "" : cause.getMessage().strip();
        if (!retry.isEmpty() && !message.contains("Retry after")) {
            message = message.isEmpty() ? retry.strip() : message + retry;
        }
        AiRequestException copied = new AiRequestException(
                cause.kind(),
                cause.status(),
                message,
                cause,
                cause.retryAfterSeconds(),
                cause.headers());
        for (String provider : AiRequestException.pausedProvidersOf(cause)) {
            copied = copied.withPausedProvider(provider);
        }
        return copied;
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
                + " requests today (at least 80% of the daily limit).");
    }

    private Slot slot(int index) {
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        return slots.get(index);
    }

    private String describe(Slot slot, long nowMillis, boolean assignActive) {
        if (slot.dailyLimit > 0 && slot.requests.get() >= slot.dailyLimit) {
            return "LIMIT REACHED (" + slot.requests.get() + "/" + slot.dailyLimit + ")";
        }
        if (nowMillis < slot.unavailableUntil) {
            String when = Instant.ofEpochMilli(slot.unavailableUntil).atZone(zone).format(CLOCK);
            return "COOLDOWN until " + when;
        }
        return assignActive ? "ACTIVE" : "READY";
    }

    private boolean providerDailyBlocked(String provider) {
        boolean any = false;
        for (Slot slot : slots) {
            if (!slot.provider.equals(provider)) {
                continue;
            }
            any = true;
            if (slot.dailyLimit <= 0 || slot.requests.get() < slot.dailyLimit) {
                return false;
            }
        }
        return any;
    }

    private Slot findQueueSlot(String provider, String model) {
        if (provider == null || model == null) {
            return null;
        }
        for (Slot slot : slots) {
            if (slot.provider.equals(provider) && slot.model.equals(model)) {
                return slot;
            }
        }
        return null;
    }

    private Slot fallbackSlot(String provider, String model) {
        String id = provider + "|" + model;
        Slot existing = fallbackSlots.get(id);
        if (existing != null) {
            return existing;
        }
        Slot created = new Slot(-1, provider, model, 0);
        fallbackSlots.put(id, created);
        providerCounts.putIfAbsent(provider, new AtomicInteger());
        return created;
    }

    public enum Hold {
        NONE, DAILY, HEADER, ERROR
    }

    public record FallbackPlan(boolean allowed, boolean dedicated, int queueIndex, String provider, String model) {
        public static FallbackPlan blocked() {
            return new FallbackPlan(false, false, -1, "", "");
        }

        public static FallbackPlan queue(int index, String provider, String model) {
            return new FallbackPlan(true, false, index, provider, model);
        }

        public static FallbackPlan dedicated(String provider, String model) {
            return new FallbackPlan(true, true, -1, provider, model);
        }
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
            int rejected,
            String state
    ) {
    }

    private static final class Slot {
        private final int index;
        private final String provider;
        private final String model;
        private final int dailyLimit;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private volatile long unavailableUntil;
        private volatile Hold hold = Hold.NONE;
        private volatile Long remainingRequests;
        private volatile Long remainingTokens;
        private volatile boolean warned;
        private volatile AiRequestException lastError;
        private volatile long lastFailedAt;

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
