package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.config.SecretMask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Daily quota admission. A call is allowed when every applicable cap still has
 * {@code spent + reserved < limit}. The reservation is the estimate from {@link QuotaEstimates}
 * (or one request). It is released when the call finishes, and the ledger then stores the real usage.
 * <p>
 * Quotas apply only while {@link QuotaSettings#enabled()} is true. A limit of 0 is not a cap.
 * The most generous matching group wins; 0 on a field beats any positive number.
 * A group's {@code requests-per-day} replaces {@code limits.player-requests-per-day} for that player.
 * Consumer caps apply only to {@link RequestOrigin#API}, and {@code default} is per plugin.
 * Moderation spends the server cap and a queue-row cap, not the player cap.
 */
public final class QuotaPolicy implements RowTokens {

    private static final long MEMBERSHIP_TTL_MILLIS = 60_000L;

    private final TokenLedger ledger;
    private final Supplier<LocalDate> today;
    private final LongSupplier clock;
    private final Logger logger;
    private final Supplier<Iterable<String>> secrets;
    private final Object lock = new Object();
    private final ConcurrentHashMap<UUID, Cached> membership = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Hold> tracked = new ConcurrentHashMap<>();

    private volatile QuotaSettings settings = QuotaSettings.off();
    private Book book;
    private final Set<String> logged = new HashSet<>();

    public QuotaPolicy(
            TokenLedger ledger,
            Supplier<LocalDate> today,
            LongSupplier clock,
            Logger logger,
            Supplier<Iterable<String>> secrets
    ) {
        this.ledger = ledger;
        this.today = today == null ? LocalDate::now : today;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.logger = logger == null ? Logger.getLogger("nexusai.quota") : logger;
        this.secrets = secrets == null ? List::of : secrets;
    }

    public void apply(QuotaSettings next) {
        this.settings = next == null ? QuotaSettings.off() : next;
    }

    public QuotaSettings settings() {
        return settings;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public boolean groupsConfigured() {
        return settings.enabled() && settings.hasGroups();
    }

    public Set<String> groupNames() {
        return settings.groups().keySet();
    }

    /** Drops the 60-second permission cache. Called on reload. */
    public void clearMembership() {
        membership.clear();
    }

    /** Drops one player. Called when that player quits. */
    public void forget(UUID playerId) {
        if (playerId != null) {
            membership.remove(playerId);
            names.remove(playerId);
        }
    }

    /** Remembers a display name for quota logs. The name is masked with the configured secrets. */
    public void noteName(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }
        names.put(playerId, playerName);
    }

    /**
     * Logs the first time {@code scope} is observed at or over its cap today.
     * Status output can call {@link #used} without logging. Admission calls this.
     */
    public void noteIfExhausted(String scope, long used, long limit) {
        if (!settings.enabled() || limit <= 0L || used < limit || scope == null || scope.isBlank()) {
            return;
        }
        synchronized (lock) {
            align();
            note(scope, used, limit);
        }
    }

    /**
     * Stores the groups this player holds. {@code heldNames} are the group names, not the permission nodes.
     * An empty collection means the player was checked and has no group.
     * The result is kept for 60 seconds.
     */
    public void remember(UUID playerId, Collection<String> heldNames) {
        if (playerId == null) {
            return;
        }
        Membership resolved = Membership.resolve(settings, heldNames);
        membership.put(playerId, new Cached(resolved, clock.getAsLong() + MEMBERSHIP_TTL_MILLIS));
    }

    /** True when group permissions must be read again on the player thread. */
    public boolean needsGroupRead(UUID playerId) {
        if (!groupsConfigured() || playerId == null) {
            return false;
        }
        Cached cached = membership.get(playerId);
        return cached == null || !cached.fresh(clock.getAsLong());
    }

    /**
     * True when a fresh group membership replaces {@code limits.player-requests-per-day}.
     * A stale or missing cache does not replace it.
     */
    public boolean replacesPlayerDay(UUID playerId) {
        if (!groupsConfigured() || playerId == null) {
            return false;
        }
        Cached cached = membership.get(playerId);
        return cached != null && cached.fresh(clock.getAsLong()) && cached.membership.grouped;
    }

    /**
     * Checks every applicable cap and, when all pass, reserves. A denied call reserves nothing.
     * A disabled policy or a charge with no positive cap returns an allowed no-op hold.
     */
    public Decision tryReserve(Charge charge) {
        if (charge == null || !settings.enabled()) {
            return Decision.allow(Hold.NONE);
        }
        synchronized (lock) {
            TokenLedger.Snapshot snap = align();
            List<Slice> slices = slices(charge, snap);
            for (Slice slice : slices) {
                long used = slice.spent + slice.reserved;
                if (used >= slice.limit) {
                    return Decision.deny(note(slice.scope, used, slice.limit));
                }
            }
            Hold hold = Hold.NONE;
            for (Slice slice : slices) {
                add(slice);
                hold = hold.plus(book.day, slice);
            }
            return Decision.allow(hold);
        }
    }

    /**
     * Queue-row token cap. Checked when a row is chosen, not at the earlier admission.
     * {@code limit <= 0} does not cap the row. A denied row is skipped.
     */
    public Decision tryReserveRow(String storageId, long limit, long estimate) {
        if (!settings.enabled() || limit <= 0L || storageId == null || storageId.isBlank()) {
            return Decision.allow(Hold.NONE);
        }
        long amount = Math.max(0L, estimate);
        synchronized (lock) {
            TokenLedger.Snapshot snap = align();
            long spent = count(snap.rows(), storageId).total();
            long reserved = book.rows.getOrDefault(storageId, 0L);
            if (spent + reserved >= limit) {
                return Decision.deny(note("row " + storageId, spent + reserved, limit));
            }
            book.rows.merge(storageId, amount, Long::sum);
            return Decision.allow(Hold.row(book.day, storageId, amount));
        }
    }

    public void release(Hold hold) {
        if (hold == null || hold == Hold.NONE || hold.noop) {
            return;
        }
        if (!hold.released.compareAndSet(false, true)) {
            return;
        }
        synchronized (lock) {
            align();
            if (book == null || hold.day == null || !hold.day.equals(book.day)) {
                return;
            }
            if (hold.serverTokens > 0L) {
                book.serverTokens = Math.max(0L, book.serverTokens - hold.serverTokens);
            }
            if (hold.player != null && (hold.playerTokens > 0L || hold.playerRequests > 0L)) {
                long[] slot = book.players.get(hold.player);
                if (slot != null) {
                    slot[0] = Math.max(0L, slot[0] - hold.playerTokens);
                    slot[1] = Math.max(0L, slot[1] - hold.playerRequests);
                }
            }
            if (hold.consumer != null && (hold.consumerTokens > 0L || hold.consumerRequests > 0L)) {
                long[] slot = book.consumers.get(hold.consumer);
                if (slot != null) {
                    slot[0] = Math.max(0L, slot[0] - hold.consumerTokens);
                    slot[1] = Math.max(0L, slot[1] - hold.consumerRequests);
                }
            }
            if (hold.rowKey != null && hold.rowTokens > 0L) {
                long left = book.rows.getOrDefault(hold.rowKey, 0L) - hold.rowTokens;
                if (left <= 0L) {
                    book.rows.remove(hold.rowKey);
                } else {
                    book.rows.put(hold.rowKey, left);
                }
            }
        }
    }

    /** Keeps {@code hold} until {@link #releaseTracked} for this request id. */
    public void track(long requestId, Hold hold) {
        if (hold == null || hold == Hold.NONE || hold.noop) {
            return;
        }
        Hold previous = tracked.put(requestId, hold);
        if (previous != null) {
            release(previous);
        }
    }

    public void releaseTracked(long requestId) {
        Hold hold = tracked.remove(requestId);
        if (hold != null) {
            release(hold);
        }
    }

    @Override
    public long used(String storageId) {
        if (!settings.enabled() || storageId == null || storageId.isBlank() || ledger == null) {
            return 0L;
        }
        synchronized (lock) {
            TokenLedger.Snapshot snap = align();
            return count(snap.rows(), storageId).total() + book.rows.getOrDefault(storageId, 0L);
        }
    }

    public io.github.neareststep.nexusai.api.QuotaStatus consumerStatus(String pluginName) {
        String name = pluginName == null || pluginName.isBlank() ? "nexusai" : pluginName;
        if (ledger == null) {
            return io.github.neareststep.nexusai.api.QuotaStatus.empty(today.get());
        }
        TokenLedger.Snapshot snap = ledger.snapshot();
        TokenLedger.Counts counts = count(snap.consumers(), name);
        if (!settings.enabled()) {
            return io.github.neareststep.nexusai.api.QuotaStatus.of(
                    snap.day(), counts.total(), 0L, false, counts.requests(), 0L, false);
        }
        QuotaSettings.ConsumerLimit limit = settings.consumer(name);
        return io.github.neareststep.nexusai.api.QuotaStatus.of(
                snap.day(),
                counts.total(),
                limit.tokensPerDay(),
                limit.tokensPerDay() > 0L,
                counts.requests(),
                limit.requestsPerDay(),
                limit.requestsPerDay() > 0L);
    }

    /** Effective player token cap. Empty when quotas are off, there is no player, or the cap is 0. */
    public java.util.OptionalLong playerTokenCap(UUID playerId) {
        if (!settings.enabled() || playerId == null) {
            return java.util.OptionalLong.empty();
        }
        Membership membership = currentMembership(playerId);
        long cap = membership.tokensPerDay;
        if (cap <= 0L) {
            return java.util.OptionalLong.empty();
        }
        return java.util.OptionalLong.of(cap);
    }

    private Membership currentMembership(UUID playerId) {
        Cached cached = membership.get(playerId);
        if (cached != null && cached.fresh(clock.getAsLong())) {
            return cached.membership;
        }
        return Membership.ungrouped(settings.playerTokensPerDay());
    }

    private TokenLedger.Snapshot align() {
        TokenLedger.Snapshot snap = ledger == null
                ? new TokenLedger.Snapshot(today.get(), TokenLedger.Counts.zero(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), List.of())
                : ledger.snapshot();
        if (book == null || snap.day() == null || !snap.day().equals(book.day)) {
            if (book == null || snap.day() == null || book.day == null || snap.day().isAfter(book.day)) {
                book = new Book(snap.day() == null ? today.get() : snap.day());
                logged.clear();
            }
        }
        return snap;
    }

    private List<Slice> slices(Charge charge, TokenLedger.Snapshot snap) {
        List<Slice> slices = new ArrayList<>();
        if (settings.serverTokensPerDay() > 0L) {
            slices.add(new Slice(
                    "server",
                    settings.serverTokensPerDay(),
                    snap.server().total(),
                    book.serverTokens,
                    charge.tokens,
                    Kind.SERVER,
                    null,
                    null));
        }
        UUID player = TokenLedger.player(charge.origin, charge.playerId);
        if (player != null) {
            Membership member = charge.membership != null ? charge.membership : currentMembership(player);
            TokenLedger.Counts spent = count(snap.players(), player.toString());
            long[] reserved = book.players.getOrDefault(player, new long[2]);
            if (member.tokensPerDay > 0L) {
                String scope = "player " + label(charge.playerName, player);
                slices.add(new Slice(scope, member.tokensPerDay, spent.total(), reserved[0], charge.tokens,
                        Kind.PLAYER_TOKENS, player, null));
            }
            if (member.grouped && member.requestsPerDay > 0L) {
                String scope = "player " + label(charge.playerName, player);
                slices.add(new Slice(scope, member.requestsPerDay, spent.requests(), reserved[1], 1L,
                        Kind.PLAYER_REQUESTS, player, null));
            }
        }
        if (charge.origin == RequestOrigin.API) {
            String consumer = charge.consumer == null || charge.consumer.isBlank() ? "nexusai" : charge.consumer;
            QuotaSettings.ConsumerLimit limit = settings.consumer(consumer);
            TokenLedger.Counts spent = count(snap.consumers(), consumer);
            long[] reserved = book.consumers.getOrDefault(consumer, new long[2]);
            if (limit.tokensPerDay() > 0L) {
                slices.add(new Slice("consumer " + consumer, limit.tokensPerDay(), spent.total(), reserved[0],
                        charge.tokens, Kind.CONSUMER_TOKENS, null, consumer));
            }
            if (limit.requestsPerDay() > 0L) {
                slices.add(new Slice("consumer " + consumer, limit.requestsPerDay(), spent.requests(), reserved[1],
                        1L, Kind.CONSUMER_REQUESTS, null, consumer));
            }
        }
        return slices;
    }

    private void add(Slice slice) {
        switch (slice.kind) {
            case SERVER -> book.serverTokens += slice.amount;
            case PLAYER_TOKENS -> playerSlot(slice.player)[0] += slice.amount;
            case PLAYER_REQUESTS -> playerSlot(slice.player)[1] += slice.amount;
            case CONSUMER_TOKENS -> consumerSlot(slice.consumer)[0] += slice.amount;
            case CONSUMER_REQUESTS -> consumerSlot(slice.consumer)[1] += slice.amount;
            case ROW -> book.rows.merge(slice.consumer, slice.amount, Long::sum);
        }
    }

    private long[] playerSlot(UUID player) {
        return book.players.computeIfAbsent(player, ignored -> new long[2]);
    }

    private long[] consumerSlot(String consumer) {
        return book.consumers.computeIfAbsent(consumer, ignored -> new long[2]);
    }

    private String note(String scope, long used, long limit) {
        String message = "Token quota reached: " + scope + " " + used + "/" + limit;
        if (book != null && logged.add(book.day + "|" + scope)) {
            Iterable<String> known = secrets.get();
            logger.info(SecretMask.redact(message, known == null ? List.of() : known));
        }
        return message;
    }

    private static TokenLedger.Counts count(Map<String, TokenLedger.Counts> map, String key) {
        if (map == null || key == null) {
            return TokenLedger.Counts.zero();
        }
        TokenLedger.Counts counts = map.get(key);
        return counts == null ? TokenLedger.Counts.zero() : counts;
    }

    private String label(String name, UUID player) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        String cached = player == null ? null : names.get(player);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        return player == null ? "" : player.toString();
    }

    /**
     * One admission. {@code membership} overrides the cache when non-null (tests and a read
     * that just happened). {@code tokens} is the reservation estimate.
     */
    public record Charge(
            RequestOrigin origin,
            String consumer,
            UUID playerId,
            String playerName,
            long tokens,
            Membership membership
    ) {
        public Charge {
            tokens = Math.max(0L, tokens);
        }

        public static Charge of(CallTrace trace, long tokens, String playerName) {
            if (trace == null) {
                return new Charge(RequestOrigin.PLACEHOLDER, "nexusai", null, playerName, tokens, null);
            }
            return new Charge(trace.origin(), trace.consumer(), trace.playerId(), playerName, tokens, null);
        }
    }

    /**
     * Resolved group for one player. {@code grouped} false uses {@code tokensPerDay} as the
     * default player token cap and does not replace the player request-per-day limit.
     * A grouped field of 0 is unlimited.
     */
    public static final class Membership {
        final boolean grouped;
        final long tokensPerDay;
        final long requestsPerDay;

        private Membership(boolean grouped, long tokensPerDay, long requestsPerDay) {
            this.grouped = grouped;
            this.tokensPerDay = Math.max(0L, tokensPerDay);
            this.requestsPerDay = Math.max(0L, requestsPerDay);
        }

        public static Membership ungrouped(long playerTokensPerDay) {
            return new Membership(false, playerTokensPerDay, 0L);
        }

        public static Membership group(long tokensPerDay, long requestsPerDay) {
            return new Membership(true, tokensPerDay, requestsPerDay);
        }

        public boolean grouped() {
            return grouped;
        }

        public long tokensPerDay() {
            return tokensPerDay;
        }

        public long requestsPerDay() {
            return requestsPerDay;
        }

        static Membership resolve(QuotaSettings settings, Collection<String> heldNames) {
            long fallback = settings == null ? 0L : settings.playerTokensPerDay();
            if (settings == null || heldNames == null || heldNames.isEmpty() || settings.groups().isEmpty()) {
                return ungrouped(fallback);
            }
            boolean any = false;
            boolean tokensInit = false;
            boolean requestsInit = false;
            long tokens = 0L;
            long requests = 0L;
            for (String name : heldNames) {
                if (name == null) {
                    continue;
                }
                QuotaSettings.GroupLimit group = settings.groups().get(name);
                if (group == null) {
                    continue;
                }
                any = true;
                tokens = merge(tokensInit, tokens, group.tokensPerDay());
                tokensInit = true;
                requests = merge(requestsInit, requests, group.requestsPerDay());
                requestsInit = true;
            }
            if (!any) {
                return ungrouped(fallback);
            }
            return group(tokens, requests);
        }

        /** 0 beats any positive cap. Otherwise the larger cap wins. */
        static long merge(boolean initialized, long current, long candidate) {
            if (!initialized) {
                return candidate;
            }
            if (current == 0L || candidate == 0L) {
                return 0L;
            }
            return Math.max(current, candidate);
        }
    }

    public static final class Decision {
        private final boolean allowed;
        private final Hold hold;
        private final String message;

        private Decision(boolean allowed, Hold hold, String message) {
            this.allowed = allowed;
            this.hold = hold == null ? Hold.NONE : hold;
            this.message = message == null ? "" : message;
        }

        static Decision allow(Hold hold) {
            return new Decision(true, hold, "");
        }

        static Decision deny(String message) {
            return new Decision(false, Hold.NONE, message == null || message.isBlank()
                    ? "Token quota reached" : message);
        }

        public boolean allowed() {
            return allowed;
        }

        public Hold hold() {
            return hold;
        }

        public String message() {
            return message;
        }
    }

    public static final class Hold {
        static final Hold NONE = new Hold(true, null, 0L, null, 0L, 0L, null, 0L, 0L, null, 0L);

        private final boolean noop;
        private final LocalDate day;
        private final long serverTokens;
        private final UUID player;
        private final long playerTokens;
        private final long playerRequests;
        private final String consumer;
        private final long consumerTokens;
        private final long consumerRequests;
        private final String rowKey;
        private final long rowTokens;
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Hold(
                boolean noop,
                LocalDate day,
                long serverTokens,
                UUID player,
                long playerTokens,
                long playerRequests,
                String consumer,
                long consumerTokens,
                long consumerRequests,
                String rowKey,
                long rowTokens
        ) {
            this.noop = noop;
            this.day = day;
            this.serverTokens = serverTokens;
            this.player = player;
            this.playerTokens = playerTokens;
            this.playerRequests = playerRequests;
            this.consumer = consumer;
            this.consumerTokens = consumerTokens;
            this.consumerRequests = consumerRequests;
            this.rowKey = rowKey;
            this.rowTokens = rowTokens;
        }

        static Hold row(LocalDate day, String storageId, long tokens) {
            return new Hold(tokens <= 0L, day, 0L, null, 0L, 0L, null, 0L, 0L, storageId, tokens);
        }

        Hold plus(LocalDate day, Slice slice) {
            if (slice.amount <= 0L) {
                return this == NONE ? new Hold(true, day, 0L, null, 0L, 0L, null, 0L, 0L, null, 0L) : this;
            }
            LocalDate when = this.day == null ? day : this.day;
            return new Hold(
                    false,
                    when,
                    serverTokens + (slice.kind == Kind.SERVER ? slice.amount : 0L),
                    slice.kind == Kind.PLAYER_TOKENS || slice.kind == Kind.PLAYER_REQUESTS ? slice.player : player,
                    playerTokens + (slice.kind == Kind.PLAYER_TOKENS ? slice.amount : 0L),
                    playerRequests + (slice.kind == Kind.PLAYER_REQUESTS ? slice.amount : 0L),
                    slice.kind == Kind.CONSUMER_TOKENS || slice.kind == Kind.CONSUMER_REQUESTS ? slice.consumer : consumer,
                    consumerTokens + (slice.kind == Kind.CONSUMER_TOKENS ? slice.amount : 0L),
                    consumerRequests + (slice.kind == Kind.CONSUMER_REQUESTS ? slice.amount : 0L),
                    rowKey,
                    rowTokens);
        }

        public void releaseWith(QuotaPolicy policy) {
            if (policy != null) {
                policy.release(this);
            }
        }
    }

    private enum Kind {
        SERVER, PLAYER_TOKENS, PLAYER_REQUESTS, CONSUMER_TOKENS, CONSUMER_REQUESTS, ROW
    }

    private static final class Slice {
        private final String scope;
        private final long limit;
        private final long spent;
        private final long reserved;
        private final long amount;
        private final Kind kind;
        private final UUID player;
        private final String consumer;

        private Slice(
                String scope,
                long limit,
                long spent,
                long reserved,
                long amount,
                Kind kind,
                UUID player,
                String consumer
        ) {
            this.scope = scope;
            this.limit = limit;
            this.spent = spent;
            this.reserved = reserved;
            this.amount = amount;
            this.kind = kind;
            this.player = player;
            this.consumer = consumer;
        }
    }

    private static final class Book {
        private final LocalDate day;
        private long serverTokens;
        private final Map<UUID, long[]> players = new HashMap<>();
        private final Map<String, long[]> consumers = new HashMap<>();
        private final Map<String, Long> rows = new HashMap<>();

        private Book(LocalDate day) {
            this.day = day;
        }
    }

    private static final class Cached {
        private final Membership membership;
        private final long expiresAt;

        private Cached(Membership membership, long expiresAt) {
            this.membership = membership;
            this.expiresAt = expiresAt;
        }

        private boolean fresh(long now) {
            return now < expiresAt;
        }
    }
}
