package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.WorldContextLimits;
import io.github.neareststep.nexusai.generate.RegionTasks;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micro-benchmarks for the PR description. Disabled path must stay near zero work.
 * Region-thread capture is measured separately from off-thread format. The Proxy-based
 * fixture overstates absolute region time for a full r=2 cube; the budget gate uses a
 * small sample so the assertion stays about capture cost, not mock overhead.
 */
class WorldContextBenchmarkTest {

    @AfterEach
    void reset() {
        RegionOwnership.reset();
    }

    @Test
    void regionCaptureStaysUnderBudgetAndFormatIsSeparate() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        Player player = WorldContextPlayerFixture.plains(
                "Bench",
                Map.of("dirt", 64, "cobblestone", 32, "oak_log", 16),
                WorldContextPlayerFixture.cube(Material.GRASS_BLOCK, Material.DIRT),
                reads);
        RegionTasks regions = owning();
        Plugin plugin = plugin();

        WorldContextService off = new WorldContextService(
                plugin, regions, Runnable::run, WorldContextSettings.defaults());
        // Small sample for the hard budget: region work only, well under 0.2 ms.
        WorldContextService tight = new WorldContextService(
                plugin, regions, Runnable::run,
                new WorldContextSettings(true, 0, 1, 4, 8, 400, 0));
        // Full default cube for reporting (Proxy overhead is not a Paper figure).
        WorldContextService full = new WorldContextService(
                plugin, regions, Runnable::run,
                new WorldContextSettings(true, 2, 125, 16, 36, 400, 0));

        for (int i = 0; i < 200; i++) {
            off.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
            tight.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
            full.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        }
        reads.set(0);
        tight.resetTiming();
        full.resetTiming();

        long offBest = Long.MAX_VALUE;
        for (int i = 0; i < 500; i++) {
            long started = System.nanoTime();
            assertEquals("", off.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS));
            offBest = Math.min(offBest, System.nanoTime() - started);
        }
        int readsAfterOff = reads.get();

        String sample = "";
        for (int i = 0; i < 500; i++) {
            sample = tight.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        }
        for (int i = 0; i < 200; i++) {
            full.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        }

        long regionBest = tight.bestRegionNanos();
        long formatBest = tight.bestFormatNanos();
        long fullRegion = full.bestRegionNanos();
        long fullFormat = full.bestFormatNanos();

        System.out.printf(
                "world-context bench: disabled_best_ns=%d region_best_ns=%d format_best_ns=%d "
                        + "full_cube_region_ns=%d full_cube_format_ns=%d enabled_tokens=%d "
                        + "block_reads_during_disabled=%d%n",
                offBest,
                regionBest,
                formatBest,
                fullRegion,
                fullFormat,
                WorldContextFormatter.estimatedTokens(sample),
                readsAfterOff);

        assertEquals(0, readsAfterOff, "disabled path must not read blocks");
        assertTrue(sample.startsWith("World:\n"));
        assertTrue(regionBest > 0L);
        assertTrue(formatBest > 0L);
        assertTrue(regionBest < TimeUnit.MICROSECONDS.toNanos(200),
                "small-sample region capture best must be well under 0.2ms, was " + regionBest + " ns");
        assertTrue(formatBest < TimeUnit.MICROSECONDS.toNanos(200),
                "format best should stay under 0.2ms on the worker, was " + formatBest + " ns");
    }

    @Test
    void cooldownSkipsRegionReadWithinWindow() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        Player player = WorldContextPlayerFixture.plains(
                "Cool",
                Map.of("dirt", 1),
                packed -> Material.STONE,
                reads);
        RegionTasks regions = owning();
        Plugin plugin = plugin();
        AtomicInteger millis = new AtomicInteger(1_000);
        WorldContextService service = new WorldContextService(
                plugin,
                regions,
                Runnable::run,
                new WorldContextSettings(true, 1, 27, 4, 4, 200, 250),
                millis::get);
        String first = service.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        int readsAfterFirst = reads.get();
        millis.set(1_100);
        String second = service.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        assertEquals(first, second);
        assertEquals(readsAfterFirst, reads.get());
        assertEquals(1, service.cooldownHits());
        millis.set(1_300);
        service.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        assertTrue(reads.get() > readsAfterFirst);
    }

    private static RegionTasks owning() {
        return new RegionTasks() {
            @Override
            public boolean owns(Player p) {
                return true;
            }

            @Override
            public void run(Plugin plugin, Player p, Runnable body, Runnable retired) {
                body.run();
            }
        };
    }

    private static Plugin plugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> "getName".equals(method.getName()) ? "NexusAI" : null);
    }
}
