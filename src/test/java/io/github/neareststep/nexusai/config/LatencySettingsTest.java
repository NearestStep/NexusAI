package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencySettingsTest {

    @Test
    void bundledDefaultsLeaveLatencyOff() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        LatencySettings settings = LatencySettings.read(yaml);
        assertFalse(settings.streamingEnabled());
        assertFalse(settings.thinkingEnabled());
        assertFalse(settings.timeoutEnabled());
        assertEquals(48, settings.charsPerChunk());
        assertEquals(40, settings.chunkDelayMillis());
        assertEquals(15_000, settings.timeoutMillis());
        assertTrue(yaml.getBoolean("cache.enabled"));
        assertEquals(0L, yaml.getLong("cache.max-memory-bytes"));
    }

    @Test
    void eachToggleIsIndependentAndClamped() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("latency.streaming.enabled", true);
        yaml.set("latency.streaming.chars-per-chunk", 2);
        yaml.set("latency.streaming.chunk-delay-millis", 9_000);
        yaml.set("latency.thinking-indicator.enabled", true);
        yaml.set("latency.timeout.enabled", false);
        yaml.set("latency.timeout.millis", 50);
        LatencySettings settings = LatencySettings.read(yaml);
        assertTrue(settings.streamingEnabled());
        assertTrue(settings.thinkingEnabled());
        assertFalse(settings.timeoutEnabled());
        assertEquals(8, settings.charsPerChunk());
        assertEquals(2_000, settings.chunkDelayMillis());
        assertEquals(100, settings.timeoutMillis());
    }
}
