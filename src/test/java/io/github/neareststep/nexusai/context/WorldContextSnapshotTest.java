package io.github.neareststep.nexusai.context;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextSnapshotTest {

    @AfterEach
    void reset() {
        RegionOwnership.reset();
    }

    @Test
    void captureRespectsRadiusVolumeAndSkipsAir() {
        AtomicInteger reads = new AtomicInteger();
        RegionOwnership.install(p -> true);
        var player = WorldContextPlayerFixture.plains(
                "Steve",
                Map.of("dirt", 3, "stone", 1),
                packed -> {
                    int dx = (short) (packed >> 16);
                    int dy = (byte) ((packed >> 8) & 0xff);
                    int dz = (byte) (packed & 0xff);
                    if (dx == 0 && dy == 0 && dz == 0) {
                        return Material.DIRT;
                    }
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1) {
                        return Material.STONE;
                    }
                    return Material.AIR;
                },
                reads);
        WorldContextSettings limits = new WorldContextSettings(true, 1, 27, 8, 8, 400, 0);
        WorldContextSnapshot snapshot = WorldContextSnapshot.capture(player, limits);
        assertEquals("Steve", snapshot.playerName());
        assertEquals("world", snapshot.worldName());
        assertEquals("plains", snapshot.biome());
        assertEquals(0, snapshot.blockX());
        assertEquals(64, snapshot.blockY());
        assertEquals(0, snapshot.blockZ());
        assertEquals(27, snapshot.blocksExamined());
        // nearby scan plus one Location.getBlock() for biome
        assertEquals(28, reads.get());
        assertEquals(3, snapshot.inventoryTotals().get("dirt"));
        assertTrue(snapshot.nearbyTotals().containsKey("stone"));
        assertTrue(snapshot.nearbyTotals().keySet().stream().noneMatch(id -> id.contains("air")));
    }

    @Test
    void materialKeyUsesKeyedPath() {
        assertEquals("dirt", WorldContextSnapshot.materialKey(Material.DIRT));
        assertEquals("grass_block", WorldContextSnapshot.materialKey(Material.GRASS_BLOCK));
        assertEquals("", WorldContextSnapshot.materialKey(null));
        assertEquals("", WorldContextSnapshot.materialKey("dirt"));
    }
}
