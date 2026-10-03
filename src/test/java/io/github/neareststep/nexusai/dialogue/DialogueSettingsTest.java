package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueSettingsTest {

    @Test
    void bundledDefaultsLeaveSummariesOff() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        DialogueSettings settings = DialogueSettings.read(yaml);
        assertFalse(settings.summaryEnabled());
        assertEquals(2, settings.summaryThresholdTurns());
        assertEquals(400, settings.summaryMaxChars());
        assertEquals(200, settings.summaryMaxTokens());
        assertEquals("", settings.summaryProvider());
        assertEquals("", settings.summaryModel());
        assertFalse(settings.summaryPinned());
        assertEquals(2, yaml.getInt("config-version"));
    }

    @Test
    void summaryKeysAreClampedAndBothEndsPin() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("dialogue.summary.enabled", true);
        yaml.set("dialogue.summary.threshold-turns", 0);
        yaml.set("dialogue.summary.max-chars", 9_000);
        yaml.set("dialogue.summary.max-tokens", -5);
        yaml.set("dialogue.summary.provider", " Groq ");
        yaml.set("dialogue.summary.model", "");
        DialogueSettings open = DialogueSettings.read(yaml);
        assertTrue(open.summaryEnabled());
        assertEquals(1, open.summaryThresholdTurns());
        assertEquals(2_000, open.summaryMaxChars());
        assertEquals(-5, open.summaryMaxTokens());
        assertEquals("groq", open.summaryProvider());
        assertFalse(open.summaryPinned());

        yaml.set("dialogue.summary.threshold-turns", 99);
        yaml.set("dialogue.summary.max-chars", 10);
        yaml.set("dialogue.summary.model", " llama ");
        DialogueSettings pinned = DialogueSettings.read(yaml);
        assertEquals(16, pinned.summaryThresholdTurns());
        assertEquals(100, pinned.summaryMaxChars());
        assertEquals("llama", pinned.summaryModel());
        assertTrue(pinned.summaryPinned());
    }

    @Test
    void oldConstructorLeavesSummariesOff() {
        DialogueSettings settings = DialogueSettings.defaults();
        assertFalse(settings.summaryEnabled());
        assertEquals("off", new SummaryStats(java.time.ZoneId.of("UTC")).text(false, 0L));
    }
}
