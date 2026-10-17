package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.WorldContextLimits;
import io.github.neareststep.nexusai.generate.RegionTasks;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextServiceTest {

    @AfterEach
    void resetOwnership() {
        RegionOwnership.reset();
    }

    @Test
    void disabledSkipsWorldReads() throws Exception {
        AtomicInteger blockReads = new AtomicInteger();
        AtomicBoolean touched = new AtomicBoolean();
        Player player = RegionPlayerFixture.throwing(touched);
        WorldContextService service = service(
                new WorldContextSettings(false, 2, 125, 16, 36, 400, 0),
                new RegionTasks() {
                    @Override
                    public boolean owns(Player target) {
                        return true;
                    }

                    @Override
                    public void run(Plugin plugin, Player target, Runnable body, Runnable retired) {
                        throw new AssertionError("should not hop when disabled");
                    }
                },
                true);
        String block = service.collect(player, WorldContextLimits.unset()).get(1, TimeUnit.SECONDS);
        assertEquals("", block);
        assertEquals(0, service.regionReads());
        assertEquals(0, blockReads.get());
        assertFalse(touched.get());
    }

    @Test
    void oneCallCollectsPlayerInventoryNearbyAndBiomeOnOwnedThread() throws Exception {
        AtomicInteger blockReads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        Player player = WorldContextPlayerFixture.plains(
                "Steve",
                Map.of("dirt", 64, "oak_log", 12),
                WorldContextPlayerFixture.cube(Material.GRASS_BLOCK, Material.DIRT),
                blockReads);
        ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "world-context-format-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            WorldContextService service = new WorldContextService(
                    dummyPlugin(),
                    owningRegions(),
                    workers,
                    new WorldContextSettings(true, 1, 27, 8, 8, 400, 0));
            service.regionThreadProbe(() -> Thread.currentThread().getName().startsWith("region-"));
            String block = service.collect(player, WorldContextLimits.unset()).get(2, TimeUnit.SECONDS);
            assertTrue(block.startsWith("World:\n"));
            assertTrue(block.contains("p=Steve"));
            assertTrue(block.contains("bio=plains"));
            assertTrue(block.contains("inv="));
            assertTrue(block.contains("dirt×64"));
            assertTrue(block.contains("near=r1"));
            assertEquals(1, service.regionReads());
            assertEquals(1, service.formatJobs());
            assertFalse(service.formattedOnRegionThread());
            assertTrue(blockReads.get() <= 28, "examined " + blockReads.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void honorsMaxBlocksOnRegionThread() throws Exception {
        AtomicInteger blockReads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        Player player = WorldContextPlayerFixture.plains(
                "Steve",
                Map.of(),
                packed -> Material.STONE,
                blockReads);
        WorldContextService service = service(
                new WorldContextSettings(true, 8, 5, 4, 0, 200, 0),
                owningRegions(),
                true);
        String block = service.collect(player, WorldContextLimits.unset()).get(2, TimeUnit.SECONDS);
        assertTrue(block.contains("near=r8"));
        // max-blocks nearby samples plus one Location.getBlock() for biome
        assertEquals(6, blockReads.get());
    }

    @Test
    void hopsToRegionOwnerThenFormatsOffThread() throws Exception {
        AtomicInteger blockReads = new AtomicInteger();
        AtomicReference<String> readThread = new AtomicReference<>();
        AtomicReference<String> formatThread = new AtomicReference<>();
        RegionOwnership.install(p -> false);
        Player player = WorldContextPlayerFixture.plains(
                "Alex",
                Map.of("stone", 1),
                WorldContextPlayerFixture.cube(Material.DIRT, Material.STONE),
                blockReads);
        ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "format-worker");
            thread.setDaemon(true);
            return thread;
        });
        RegionTasks regions = new RegionTasks() {
            @Override
            public boolean owns(Player target) {
                return RegionOwnership.owned(target);
            }

            @Override
            public void run(Plugin plugin, Player target, Runnable body, Runnable retired) {
                Thread thread = new Thread(() -> {
                    RegionOwnership.install(p -> true);
                    try {
                        readThread.set(Thread.currentThread().getName());
                        body.run();
                    } finally {
                        RegionOwnership.install(p -> false);
                    }
                }, "region-owner");
                thread.setDaemon(true);
                thread.start();
            }
        };
        try {
            WorldContextService service = new WorldContextService(
                    dummyPlugin(),
                    regions,
                    workers,
                    new WorldContextSettings(true, 1, 27, 4, 4, 200, 0));
            service.regionThreadProbe(() -> {
                formatThread.set(Thread.currentThread().getName());
                return Thread.currentThread().getName().startsWith("region-");
            });
            String block = service.collect(player, WorldContextLimits.unset()).get(2, TimeUnit.SECONDS);
            assertTrue(block.contains("p=Alex"));
            assertEquals("region-owner", readThread.get());
            assertEquals("format-worker", formatThread.get());
            assertFalse(service.formattedOnRegionThread());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void perCallRadiusOverrideIsApplied() throws Exception {
        AtomicInteger blockReads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        Player player = WorldContextPlayerFixture.plains(
                "Steve",
                Map.of(),
                packed -> Material.STONE,
                blockReads);
        WorldContextService service = service(
                new WorldContextSettings(true, 8, 1331, 4, 0, 200, 0),
                owningRegions(),
                true);
        String block = service.collect(player, WorldContextLimits.builder().radius(0).maxBlocks(1).build())
                .get(2, TimeUnit.SECONDS);
        assertTrue(block.contains("near=r0"));
        // one feet-block sample plus one Location.getBlock() for biome
        assertEquals(2, blockReads.get());
    }

    private static WorldContextService service(
            WorldContextSettings settings,
            RegionTasks regions,
            boolean owned
    ) {
        RegionOwnership.install(p -> owned);
        return new WorldContextService(
                dummyPlugin(),
                regions,
                Runnable::run,
                settings);
    }

    private static RegionTasks owningRegions() {
        return new RegionTasks() {
            @Override
            public boolean owns(Player player) {
                return RegionOwnership.owned(player);
            }

            @Override
            public void run(Plugin plugin, Player player, Runnable body, Runnable retired) {
                body.run();
            }
        };
    }

    private static Plugin dummyPlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return "NexusAI";
                    }
                    if ("toString".equals(method.getName())) {
                        return "NexusAI";
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
