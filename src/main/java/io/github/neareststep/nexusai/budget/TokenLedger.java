package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * In-memory token counters for the current local day.
 * The hot path adds to {@link LongAdder}s and does not take a lock.
 * At local midnight the server total moves into history (at most {@link #MAX_HISTORY} days,
 * newest first) and every slice starts at zero. A clock set backwards keeps the counters and
 * logs one warning until that date arrives.
 */
public final class TokenLedger {

    public static final int MAX_HISTORY = 30;
    static final int MAX_KEY_CHARS = 128;

    private final Supplier<LocalDate> today;
    private final Logger logger;
    private final AtomicReference<Generation> current;
    private final Object rollLock = new Object();
    private final AtomicLong mutations = new AtomicLong();
    private volatile MissingUsage missingUsage = MissingUsage.ESTIMATE;
    private volatile boolean clockWarned;

    public TokenLedger(Supplier<LocalDate> today, Logger logger) {
        this.today = today == null ? LocalDate::now : today;
        this.logger = logger == null ? Logger.getLogger("nexusai.tokens") : logger;
        this.current = new AtomicReference<>(new Generation(this.today.get(), List.of()));
    }

    public void missingUsage(MissingUsage mode) {
        this.missingUsage = mode == null ? MissingUsage.ESTIMATE : mode;
    }

    public MissingUsage missingUsage() {
        return missingUsage;
    }

    public long mutations() {
        return mutations.get();
    }

    /**
     * Counts one HTTP attempt. A response that is neither reported nor estimated is ignored,
     * including transport failures that carried no {@code usage} object.
     * Cache hits, in-flight joins, pool draws, and fallback text never call this.
     */
    public void record(ResponseUsage usage, CallTrace trace, String providerId, String storageKey, boolean fallbackSlot) {
        Charge charge = charge(usage, missingUsage);
        if (charge == null) {
            return;
        }
        String provider = key(providerId);
        String slice = key(storageKey);
        String consumer = consumer(trace);
        RequestOrigin origin = trace == null ? null : trace.origin();
        String originKey = origin == null ? "" : origin.name().toLowerCase(Locale.ROOT);
        UUID player = player(origin, trace == null ? null : trace.playerId());
        while (true) {
            Generation generation = current.get();
            LocalDate now = today.get();
            if (now.isAfter(generation.day)) {
                roll(generation, now);
                continue;
            }
            if (now.isBefore(generation.day)) {
                warnClock(generation.day, now);
            }
            if (!generation.enter()) {
                continue;
            }
            try {
                if (current.get() != generation) {
                    continue;
                }
                generation.server.add(charge);
                if (!provider.isEmpty()) {
                    generation.providers.computeIfAbsent(provider, ignored -> new Bucket()).add(charge);
                }
                if (!slice.isEmpty()) {
                    Map<String, Bucket> map = fallbackSlot ? generation.fallback : generation.rows;
                    map.computeIfAbsent(slice, ignored -> new Bucket()).add(charge);
                }
                if (!originKey.isEmpty()) {
                    generation.origins.computeIfAbsent(originKey, ignored -> new Bucket()).add(charge);
                }
                generation.consumers.computeIfAbsent(consumer, ignored -> new Bucket()).add(charge);
                if (player != null) {
                    generation.players.computeIfAbsent(player.toString(), ignored -> new Bucket()).add(charge);
                }
                mutations.incrementAndGet();
                return;
            } finally {
                generation.leave();
            }
        }
    }

    /** Rolls forward when {@link #today} has passed the open day. Safe to call from any thread. */
    public void catchUp() {
        Generation generation = current.get();
        LocalDate now = today.get();
        if (now.isAfter(generation.day)) {
            roll(generation, now);
        } else if (now.isBefore(generation.day)) {
            warnClock(generation.day, now);
        }
    }

    public Snapshot snapshot() {
        catchUp();
        while (true) {
            Generation generation = current.get();
            if (!generation.enter()) {
                continue;
            }
            try {
                if (current.get() != generation) {
                    continue;
                }
                return generation.snapshot();
            } finally {
                generation.leave();
            }
        }
    }

    /**
     * Replaces the open day. Used when {@code token-usage.yml} is loaded, before requests run.
     * A stored day in the past is rolled by the following {@link #catchUp()}.
     */
    public void restore(Snapshot snapshot) {
        if (snapshot == null || snapshot.day() == null) {
            return;
        }
        synchronized (rollLock) {
            current.set(Generation.restore(snapshot));
            clockWarned = false;
        }
    }

    private void roll(Generation expected, LocalDate now) {
        synchronized (rollLock) {
            Generation generation = current.get();
            if (generation != expected || !now.isAfter(generation.day)) {
                return;
            }
            generation.close();
            while (generation.inflight() > 0) {
                Thread.onSpinWait();
            }
            Counts server = generation.server.freeze();
            List<DayTotal> history = new ArrayList<>(generation.history.size() + 1);
            history.add(new DayTotal(generation.day, server));
            history.addAll(generation.history);
            if (history.size() > MAX_HISTORY) {
                history = new ArrayList<>(history.subList(0, MAX_HISTORY));
            }
            Generation next = new Generation(now, List.copyOf(history));
            current.compareAndSet(generation, next);
            clockWarned = false;
        }
    }

    private void warnClock(LocalDate stored, LocalDate now) {
        if (clockWarned) {
            return;
        }
        synchronized (rollLock) {
            if (clockWarned) {
                return;
            }
            clockWarned = true;
            logger.warning("token-usage.yml day " + stored + " is ahead of " + now
                    + ". Counters stay until that date.");
        }
    }

    static Charge charge(ResponseUsage usage, MissingUsage mode) {
        if (usage == null || (!usage.reported() && !usage.estimated())) {
            return null;
        }
        long prompt = usage.promptTokens();
        long completion = usage.completionTokens();
        long total = usage.totalTokens();
        long estimated = 0L;
        if (usage.estimated()) {
            if (mode == MissingUsage.IGNORE) {
                prompt = 0L;
                completion = 0L;
                total = 0L;
            } else {
                estimated = total;
            }
        }
        return new Charge(prompt, completion, total, estimated);
    }

    /**
     * Player slices are placeholder-with-player, talk, greeting, summary, and API.
     * Moderation, the pool, prewarm, and {@code /nai test} stay off the player slice.
     */
    static UUID player(RequestOrigin origin, UUID playerId) {
        if (origin == null || playerId == null) {
            return null;
        }
        return switch (origin) {
            case PLACEHOLDER, TALK, TALK_GREETING, SUMMARY, API -> playerId;
            default -> null;
        };
    }

    private static String consumer(CallTrace trace) {
        if (trace == null) {
            return "nexusai";
        }
        String name = key(trace.consumer());
        return name.isEmpty() ? "nexusai" : name;
    }

    static String key(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_KEY_CHARS));
        raw.codePoints().limit(MAX_KEY_CHARS).forEach(point -> {
            if (point == '\r' || point == '\n' || point == '\0' || point == '\u2028' || point == '\u2029') {
                out.append('_');
            } else {
                out.appendCodePoint(point);
            }
        });
        return out.toString().trim();
    }

    public record Charge(long prompt, long completion, long total, long estimated) {
    }

    public record Counts(long requests, long prompt, long completion, long total, long estimated) {
        public Counts {
            requests = Math.max(0L, requests);
            prompt = Math.max(0L, prompt);
            completion = Math.max(0L, completion);
            total = Math.max(0L, total);
            estimated = Math.max(0L, estimated);
        }

        static Counts zero() {
            return new Counts(0L, 0L, 0L, 0L, 0L);
        }
    }

    public record DayTotal(LocalDate day, long requests, long prompt, long completion, long total, long estimated) {
        public DayTotal(LocalDate day, Counts counts) {
            this(day, counts.requests(), counts.prompt(), counts.completion(), counts.total(), counts.estimated());
        }

        public Counts counts() {
            return new Counts(requests, prompt, completion, total, estimated);
        }
    }

    public record Snapshot(
            LocalDate day,
            Counts server,
            Map<String, Counts> providers,
            Map<String, Counts> rows,
            Map<String, Counts> fallback,
            Map<String, Counts> origins,
            Map<String, Counts> consumers,
            Map<String, Counts> players,
            List<DayTotal> history
    ) {
        public Snapshot {
            providers = copy(providers);
            rows = copy(rows);
            fallback = copy(fallback);
            origins = copy(origins);
            consumers = copy(consumers);
            players = copy(players);
            history = history == null ? List.of() : List.copyOf(history);
            if (server == null) {
                server = Counts.zero();
            }
        }

        private static Map<String, Counts> copy(Map<String, Counts> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(source);
        }
    }

    private static final class Bucket {
        private final LongAdder requests = new LongAdder();
        private final LongAdder prompt = new LongAdder();
        private final LongAdder completion = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final LongAdder estimated = new LongAdder();

        void add(Charge charge) {
            requests.increment();
            prompt.add(charge.prompt());
            completion.add(charge.completion());
            total.add(charge.total());
            estimated.add(charge.estimated());
        }

        void seed(Counts counts) {
            if (counts.requests() != 0L) {
                requests.add(counts.requests());
            }
            if (counts.prompt() != 0L) {
                prompt.add(counts.prompt());
            }
            if (counts.completion() != 0L) {
                completion.add(counts.completion());
            }
            if (counts.total() != 0L) {
                total.add(counts.total());
            }
            if (counts.estimated() != 0L) {
                estimated.add(counts.estimated());
            }
        }

        Counts freeze() {
            return new Counts(requests.sum(), prompt.sum(), completion.sum(), total.sum(), estimated.sum());
        }
    }

    private static final class Generation {
        private final LocalDate day;
        private final List<DayTotal> history;
        private final Bucket server = new Bucket();
        private final ConcurrentHashMap<String, Bucket> providers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Bucket> rows = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Bucket> fallback = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Bucket> origins = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Bucket> consumers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Bucket> players = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger inflight = new AtomicInteger();

        private Generation(LocalDate day, List<DayTotal> history) {
            this.day = day;
            this.history = history;
        }

        static Generation restore(Snapshot snapshot) {
            Generation generation = new Generation(snapshot.day(), snapshot.history());
            generation.server.seed(snapshot.server());
            seed(generation.providers, snapshot.providers());
            seed(generation.rows, snapshot.rows());
            seed(generation.fallback, snapshot.fallback());
            seed(generation.origins, snapshot.origins());
            seed(generation.consumers, snapshot.consumers());
            seed(generation.players, snapshot.players());
            return generation;
        }

        private static void seed(ConcurrentHashMap<String, Bucket> target, Map<String, Counts> source) {
            for (Map.Entry<String, Counts> entry : source.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                Bucket bucket = new Bucket();
                bucket.seed(entry.getValue());
                target.put(entry.getKey(), bucket);
            }
        }

        boolean enter() {
            inflight.incrementAndGet();
            if (closed.get()) {
                inflight.decrementAndGet();
                return false;
            }
            return true;
        }

        void leave() {
            inflight.decrementAndGet();
        }

        void close() {
            closed.set(true);
        }

        int inflight() {
            return inflight.get();
        }

        Snapshot snapshot() {
            return new Snapshot(
                    day,
                    server.freeze(),
                    freeze(providers),
                    freeze(rows),
                    freeze(fallback),
                    freeze(origins),
                    freeze(consumers),
                    freeze(players),
                    history);
        }

        private static Map<String, Counts> freeze(ConcurrentHashMap<String, Bucket> source) {
            if (source.isEmpty()) {
                return Map.of();
            }
            Map<String, Counts> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Bucket> entry : source.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().freeze());
            }
            return copy;
        }
    }
}
