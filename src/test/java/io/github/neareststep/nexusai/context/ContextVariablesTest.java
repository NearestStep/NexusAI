package io.github.neareststep.nexusai.context;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextVariablesTest {

    @AfterEach
    void resetOwnership() {
        RegionOwnership.reset();
    }

    @Test
    void captureReadsBuiltInsWhenTheRegionIsOwned() {
        RegionOwnership.install(player -> true);
        Player player = RegionPlayerFixture.named("Steve", "lobby");
        Map<String, String> values = ContextVariables.capture(player);
        assertEquals("Steve", values.get("player"));
        assertEquals("lobby", values.get("world"));
        assertEquals("plains", values.get("biome"));
        assertEquals("day", values.get("time"));
        assertEquals("clear", values.get("weather"));
    }

    @Test
    void captureSkipsThePlayerWhenTheRegionIsNotOwned() {
        AtomicBoolean touched = new AtomicBoolean();
        RegionOwnership.install(player -> false);
        Player player = RegionPlayerFixture.throwing(touched);
        assertEquals(Map.of(), ContextVariables.capture(player));
        assertFalse(touched.get());
        assertTrue(ContextVariables.capture(null).isEmpty());
    }
}
