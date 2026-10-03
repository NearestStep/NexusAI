package io.github.neareststep.nexusai.context;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sanitized context blocks for {@code cached_} placeholders.
 * The key is {@code (playerId, promptId)}. A read on the main thread is a plain map get.
 */
public final class ContextSnapshots {

    public record Key(UUID playerId, String promptId) {
    }

    public record Snapshot(String block, long createdAtMillis) {
    }

    private final ConcurrentHashMap<Key, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> epochs = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public Snapshot get(UUID playerId, String promptId) {
        if (playerId == null || promptId == null) {
            return null;
        }
        return snapshots.get(new Key(playerId, promptId));
    }

    public void put(UUID playerId, String promptId, String block, long nowMillis) {
        if (playerId == null || promptId == null) {
            return;
        }
        snapshots.put(new Key(playerId, promptId), new Snapshot(block == null ? "" : block, nowMillis));
    }

    public boolean fresh(Snapshot snapshot, long nowMillis, Duration refresh) {
        if (snapshot == null) {
            return false;
        }
        long ttl = refresh == null ? 30_000L : Math.max(1L, refresh.toMillis());
        return nowMillis - snapshot.createdAtMillis() < ttl;
    }

    public long generation() {
        return generation.get();
    }

    public long epoch(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        return epochs.getOrDefault(playerId, 0L);
    }

    /** Drops one player's snapshots. An in-flight collect that started earlier must not put them back. */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        epochs.merge(playerId, 1L, Long::sum);
        snapshots.keySet().removeIf(key -> playerId.equals(key.playerId()));
    }

    /** Drops every snapshot. Used by {@code /nai reload}. */
    public void clear() {
        generation.incrementAndGet();
        snapshots.clear();
    }
}
