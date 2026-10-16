package io.github.neareststep.nexusai.context;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextFormatterTest {

    @Test
    void formatsCompactPlayerInventoryNearbyAndBiome() {
        Map<String, Integer> inventory = new LinkedHashMap<>();
        inventory.put("dirt", 64);
        inventory.put("iron_pickaxe", 1);
        Map<String, Integer> nearby = new LinkedHashMap<>();
        nearby.put("grass_block", 40);
        nearby.put("dirt", 30);
        WorldContextSnapshot snapshot = new WorldContextSnapshot(
                "Steve",
                "world",
                10,
                64,
                -3,
                "plains",
                inventory,
                nearby,
                125,
                2);
        String block = WorldContextFormatter.format(snapshot, WorldContextSettings.defaults());
        assertTrue(block.startsWith(WorldContextFormatter.HEADER));
        assertTrue(block.contains("p=Steve w=world @10,64,-3 bio=plains"));
        assertTrue(block.contains("inv=dirt×64,iron_pickaxe"));
        assertTrue(block.contains("near=r2 grass_block×40,dirt×30"));
        int tokens = WorldContextFormatter.estimatedTokens(block);
        assertTrue(tokens > 0);
        assertTrue(tokens <= WorldContextSettings.DOCUMENTED_MAX_BODY_TOKENS + 5,
                "documented body cap is 100 tokens; header adds a few: " + tokens);
    }

    @Test
    void honorsMaxCharsAndDocumentsTokenEstimate() {
        String longName = "a".repeat(200);
        WorldContextSnapshot snapshot = new WorldContextSnapshot(
                longName,
                "world",
                0,
                64,
                0,
                "plains",
                Map.of(),
                Map.of(),
                1,
                0);
        WorldContextSettings tight = new WorldContextSettings(true, 0, 1, 1, 0, 40, 0);
        String block = WorldContextFormatter.format(snapshot, tight);
        String body = block.substring(WorldContextFormatter.HEADER.length());
        assertEquals(41, body.codePointCount(0, body.length()));
        assertTrue(body.endsWith("…"));
        assertEquals(
                (int) Math.ceil(block.codePointCount(0, block.length()) / 4.0),
                WorldContextFormatter.estimatedTokens(block));
    }

    @Test
    void emptySnapshotYieldsEmptyBlock() {
        assertEquals("", WorldContextFormatter.format(null, WorldContextSettings.defaults()));
        assertEquals(0, WorldContextFormatter.estimatedTokens(""));
        assertEquals(0, WorldContextFormatter.estimatedTokens(null));
    }

    @Test
    void omitsEmptyInventoryAndNearbyLines() {
        WorldContextSnapshot snapshot = new WorldContextSnapshot(
                "Alex", "hub", 1, 2, 3, "desert", Map.of(), Map.of(), 0, 1);
        String block = WorldContextFormatter.format(snapshot, WorldContextSettings.defaults());
        assertTrue(block.contains("p=Alex w=hub @1,2,3 bio=desert"));
        assertFalse(block.contains("inv="));
        assertFalse(block.contains("near="));
    }

    @Test
    void ranksTotalsOffTheSnapshot() {
        Map<String, Integer> nearby = Map.of("a", 1, "b", 5, "c", 3);
        var ranked = WorldContextFormatter.topCounts(nearby, 2);
        assertEquals(2, ranked.size());
        assertEquals("b", ranked.get(0).id());
        assertEquals(5, ranked.get(0).amount());
        assertEquals("c", ranked.get(1).id());
    }
}
