package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.WorldContextLimits;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldContextSettingsTest {

    @Test
    void defaultsAreDisabledWithDocumentedCaps() {
        WorldContextSettings settings = WorldContextSettings.defaults();
        assertFalse(settings.enabled());
        assertEquals(2, settings.radius());
        assertEquals(125, settings.maxBlocks());
        assertEquals(16, settings.maxBlockTypes());
        assertEquals(36, settings.maxInventoryItems());
        assertEquals(400, settings.maxChars());
        assertEquals(250, settings.cooldownMillis());
        assertEquals(100, WorldContextSettings.DOCUMENTED_MAX_BODY_TOKENS);
        assertEquals(100, (int) Math.ceil(settings.maxChars() / 4.0));
    }

    @Test
    void readClampsAndKeepsMissingAsDefaults() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world-context.enabled", true);
        yaml.set("world-context.radius", 99);
        yaml.set("world-context.max-blocks", 0);
        yaml.set("world-context.max-chars", 10);
        yaml.set("world-context.cooldown-millis", 9000);
        WorldContextSettings settings = WorldContextSettings.read(yaml);
        assertTrue(settings.enabled());
        assertEquals(8, settings.radius());
        assertEquals(1, settings.maxBlocks());
        assertEquals(40, settings.maxChars());
        assertEquals(5000, settings.cooldownMillis());
    }

    @Test
    void resolveAppliesOverridesWithoutChangingEnabledOrCooldown() {
        WorldContextSettings base = new WorldContextSettings(false, 2, 125, 16, 36, 400, 250);
        WorldContextSettings resolved = base.resolve(WorldContextLimits.builder()
                .radius(1)
                .maxBlocks(27)
                .maxBlockTypes(4)
                .maxInventoryItems(2)
                .maxChars(80)
                .build());
        assertFalse(resolved.enabled());
        assertEquals(1, resolved.radius());
        assertEquals(27, resolved.maxBlocks());
        assertEquals(4, resolved.maxBlockTypes());
        assertEquals(2, resolved.maxInventoryItems());
        assertEquals(80, resolved.maxChars());
        assertEquals(250, resolved.cooldownMillis());
    }
}
