package io.github.neareststep.nexusai.context;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Main-thread decision for {@code %ainexus_cached_%}.
 * A missing snapshot does not start HTTP. When the collect finishes, {@code onReady} is called
 * once with the exact prompt text that belongs in the cache key. A stale snapshot is used for
 * this read and refreshed in the background without a second HTTP call from the refresh.
 */
public final class CachedContextCoordinator {

    public enum Phase {
        READY,
        PENDING
    }

    public record Decision(Phase phase, String promptText) {
    }

    private final ContextSnapshots snapshots;
    private final LongSupplier clock;
    private final Supplier<Duration> refresh;
    private final ConcurrentHashMap<ContextSnapshots.Key, Boolean> inFlight = new ConcurrentHashMap<>();

    public CachedContextCoordinator(ContextSnapshots snapshots, LongSupplier clock, Supplier<Duration> refresh) {
        this.snapshots = snapshots;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.refresh = refresh == null ? () -> Duration.ofSeconds(30) : refresh;
    }

    public Decision decide(
            UUID playerId,
            String promptId,
            String rendered,
            boolean useContext,
            long nowMillis,
            Supplier<CompletableFuture<String>> collect,
            Consumer<String> onReady
    ) {
        String text = rendered == null ? "" : rendered;
        if (!useContext || playerId == null || promptId == null || promptId.isBlank()) {
            return new Decision(Phase.READY, text);
        }
        ContextSnapshots.Snapshot snapshot = snapshots.get(playerId, promptId);
        if (snapshot == null) {
            start(playerId, promptId, text, collect, onReady, true);
            return new Decision(Phase.PENDING, text);
        }
        if (!snapshots.fresh(snapshot, nowMillis, refresh.get())) {
            start(playerId, promptId, text, collect, onReady, false);
        }
        return new Decision(Phase.READY, ContextBlock.appendUser(text, snapshot.block()));
    }

    private void start(
            UUID playerId,
            String promptId,
            String rendered,
            Supplier<CompletableFuture<String>> collect,
            Consumer<String> onReady,
            boolean notifyHttp
    ) {
        ContextSnapshots.Key key = new ContextSnapshots.Key(playerId, promptId);
        if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        long generation = snapshots.generation();
        long epoch = snapshots.epoch(playerId);
        CompletableFuture<String> future;
        try {
            future = collect == null ? null : collect.get();
        } catch (RuntimeException ex) {
            future = CompletableFuture.completedFuture("");
        }
        if (future == null) {
            future = CompletableFuture.completedFuture("");
        }
        future.whenComplete((block, error) -> {
            inFlight.remove(key, Boolean.TRUE);
            if (snapshots.generation() != generation || snapshots.epoch(playerId) != epoch) {
                return;
            }
            String wrapped = error == null && block != null ? block : "";
            snapshots.put(playerId, promptId, wrapped, clock.getAsLong());
            if (notifyHttp && onReady != null) {
                onReady.accept(ContextBlock.appendUser(rendered, wrapped));
            }
        });
    }
}
