package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.WorldContextLimits;
import io.github.neareststep.nexusai.generate.RegionTasks;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Collects world context for a prompt. Region-thread work is only {@link WorldContextSnapshot#capture};
 * sorting and formatting run on {@code formatExecutor}. When {@code world-context.enabled} is false
 * the future completes with {@code ""} and no world state is read. Within
 * {@link WorldContextSettings#cooldownMillis()} the last block for that player is returned without
 * a fresh region read.
 */
public final class WorldContextService {

    private final Plugin plugin;
    private final RegionTasks regions;
    private final Executor formatExecutor;
    private final LongSupplier clock;
    private final AtomicInteger regionReads = new AtomicInteger();
    private final AtomicInteger formatJobs = new AtomicInteger();
    private final AtomicInteger cooldownHits = new AtomicInteger();
    private final AtomicBoolean formatOnRegionThread = new AtomicBoolean();
    private final AtomicLong lastRegionNanos = new AtomicLong();
    private final AtomicLong lastFormatNanos = new AtomicLong();
    private final AtomicLong bestRegionNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong bestFormatNanos = new AtomicLong(Long.MAX_VALUE);
    private final ConcurrentHashMap<UUID, CachedBlock> cache = new ConcurrentHashMap<>();

    private volatile WorldContextSettings settings;
    private volatile Supplier<Boolean> regionThreadProbe = () -> false;

    public WorldContextService(
            Plugin plugin,
            RegionTasks regions,
            Executor formatExecutor,
            WorldContextSettings settings
    ) {
        this(plugin, regions, formatExecutor, settings, System::currentTimeMillis);
    }

    WorldContextService(
            Plugin plugin,
            RegionTasks regions,
            Executor formatExecutor,
            WorldContextSettings settings,
            LongSupplier clock
    ) {
        this.plugin = plugin;
        this.regions = regions;
        this.formatExecutor = formatExecutor;
        this.settings = settings == null ? WorldContextSettings.defaults() : settings;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public void apply(WorldContextSettings settings) {
        this.settings = settings == null ? WorldContextSettings.defaults() : settings;
        cache.clear();
    }

    public WorldContextSettings settings() {
        return settings;
    }

    /**
     * Test hook: when the supplier returns true, a format job running on that thread is recorded
     * as a region-thread format (a Folia / main-thread bug).
     */
    public void regionThreadProbe(Supplier<Boolean> probe) {
        this.regionThreadProbe = probe == null ? () -> false : probe;
    }

    public int regionReads() {
        return regionReads.get();
    }

    public int formatJobs() {
        return formatJobs.get();
    }

    public int cooldownHits() {
        return cooldownHits.get();
    }

    public boolean formattedOnRegionThread() {
        return formatOnRegionThread.get();
    }

    /** Nanoseconds of the last {@link WorldContextSnapshot#capture} on a region thread. */
    public long lastRegionNanos() {
        return lastRegionNanos.get();
    }

    /** Nanoseconds of the last format job off the region thread. */
    public long lastFormatNanos() {
        return lastFormatNanos.get();
    }

    /** Best (lowest) capture time observed since construction or {@link #resetTiming()}. */
    public long bestRegionNanos() {
        long value = bestRegionNanos.get();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    /** Best (lowest) format time observed since construction or {@link #resetTiming()}. */
    public long bestFormatNanos() {
        long value = bestFormatNanos.get();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    public void resetTiming() {
        lastRegionNanos.set(0L);
        lastFormatNanos.set(0L);
        bestRegionNanos.set(Long.MAX_VALUE);
        bestFormatNanos.set(Long.MAX_VALUE);
        regionReads.set(0);
        formatJobs.set(0);
        cooldownHits.set(0);
        formatOnRegionThread.set(false);
    }

    /**
     * Compact prompt block, or empty. Never completes exceptionally for a soft failure
     * (disabled, missing player, retired entity, rejected worker).
     */
    public CompletableFuture<String> collect(Player player, WorldContextLimits overrides) {
        WorldContextSettings current = settings;
        if (current == null || !current.enabled()) {
            return CompletableFuture.completedFuture("");
        }
        if (player == null || plugin == null || regions == null) {
            return CompletableFuture.completedFuture("");
        }
        UUID id;
        try {
            id = player.getUniqueId();
        } catch (Throwable ignored) {
            return CompletableFuture.completedFuture("");
        }
        if (id == null) {
            return CompletableFuture.completedFuture("");
        }
        long now = clock.getAsLong();
        int cooldown = current.cooldownMillis();
        if (cooldown > 0) {
            CachedBlock cached = cache.get(id);
            if (cached != null && now - cached.atMillis() < cooldown) {
                cooldownHits.incrementAndGet();
                return CompletableFuture.completedFuture(cached.block());
            }
        }
        WorldContextSettings limits = current.resolve(overrides);
        CompletableFuture<String> done = new CompletableFuture<>();
        Runnable read = () -> {
            regionReads.incrementAndGet();
            long started = System.nanoTime();
            WorldContextSnapshot snapshot;
            try {
                snapshot = WorldContextSnapshot.capture(player, limits);
            } catch (Throwable ignored) {
                done.complete("");
                return;
            } finally {
                noteRegion(System.nanoTime() - started);
            }
            scheduleFormat(done, id, snapshot, limits);
        };
        try {
            if (regions.owns(player)) {
                read.run();
            } else {
                regions.run(plugin, player, read, () -> done.complete(""));
            }
        } catch (Throwable ignored) {
            done.complete("");
        }
        return done;
    }

    private void scheduleFormat(
            CompletableFuture<String> done,
            UUID playerId,
            WorldContextSnapshot snapshot,
            WorldContextSettings limits
    ) {
        if (formatExecutor == null) {
            String block = timedFormat(snapshot, limits);
            remember(playerId, block);
            done.complete(block);
            return;
        }
        try {
            formatExecutor.execute(() -> {
                formatJobs.incrementAndGet();
                if (Boolean.TRUE.equals(regionThreadProbe.get())) {
                    formatOnRegionThread.set(true);
                }
                String block = timedFormat(snapshot, limits);
                remember(playerId, block);
                done.complete(block);
            });
        } catch (RejectedExecutionException ex) {
            done.complete("");
        } catch (Throwable ignored) {
            done.complete("");
        }
    }

    private String timedFormat(WorldContextSnapshot snapshot, WorldContextSettings limits) {
        long started = System.nanoTime();
        try {
            return safeFormat(snapshot, limits);
        } finally {
            noteFormat(System.nanoTime() - started);
        }
    }

    private void remember(UUID playerId, String block) {
        if (playerId == null || block == null || block.isEmpty()) {
            return;
        }
        cache.put(playerId, new CachedBlock(block, clock.getAsLong()));
    }

    private void noteRegion(long nanos) {
        long value = Math.max(0L, nanos);
        lastRegionNanos.set(value);
        bestRegionNanos.accumulateAndGet(value, Math::min);
    }

    private void noteFormat(long nanos) {
        long value = Math.max(0L, nanos);
        lastFormatNanos.set(value);
        bestFormatNanos.accumulateAndGet(value, Math::min);
    }

    private static String safeFormat(WorldContextSnapshot snapshot, WorldContextSettings limits) {
        try {
            return WorldContextFormatter.format(snapshot, limits);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private record CachedBlock(String block, long atMillis) {
        private CachedBlock {
            block = block == null ? "" : block;
        }
    }
}
