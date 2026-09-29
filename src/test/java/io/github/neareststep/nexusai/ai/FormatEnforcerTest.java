package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.config.FormatPreset;
import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatEnforcerTest {

    @Test
    void everyPresetEnforcesItsLimits() {
        assertEquals("**keep**", enforce("simple", "**keep**"));

        String chat = enforce("chat", "One. Two! Three? Four.");
        assertFalse(chat.contains("Four"));
        assertTrue(chat.contains("Three?"));
        assertEquals("Hello world", enforce("chat", "**Hello** world"));

        String gui = enforce("gui", "1\n2\n3\n4\n5\n6\n7");
        assertEquals(6, gui.split("\\R", -1).length);

        assertEquals("Alpha Beta Gamma Delta", enforce("name", "Alpha Beta Gamma Delta Epsilon."));
        assertFalse(enforce("name", "Shop.").endsWith("."));

        String hologram = enforce("hologram", "This hologram line is definitely longer than forty characters\nshort");
        String[] lines = hologram.split("\\R", -1);
        assertTrue(lines.length <= 4);
        for (String line : lines) {
            assertTrue(line.length() <= 40, line);
        }
        assertTrue(hologram.contains("\n"));

        String action = enforce("actionbar", "This is a very long action bar sentence that should be cut at a word boundary now.");
        assertTrue(action.length() <= 60, action);
        assertFalse(action.endsWith(" "));
        assertFalse(action.contains("boundary now"));

        String boss = enforce("bossbar", "word ".repeat(30));
        assertTrue(boss.length() <= 80, boss);
        assertFalse(boss.contains("\n"));
    }

    @Test
    void instructionIsAppendedAtTheEndOfTheSystemPrompt() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.system-prompt", "Be brief");
        PluginConfig config = new PluginConfig(yaml);
        var body = OpenAiProvider.buildBody(config, "hi", GenerationOverrides.none().withFormat("hologram"));
        String system = body.getMessages().getFirst().getContent();
        String instruction = FormatPresets.builtin("hologram").instruction();
        assertTrue(system.startsWith("Be brief"));
        assertTrue(system.contains(instruction));
        assertTrue(system.endsWith(PlayerInput.GUARD));
        assertTrue(system.indexOf("Be brief") < system.indexOf(instruction));
        assertTrue(system.indexOf(instruction) < system.lastIndexOf(PlayerInput.GUARD));
    }

    @Test
    void formatIsPartOfTheCacheKey() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        PluginConfig config = new PluginConfig(yaml);
        AiHttpClient client = new AiHttpClient(
                new io.github.neareststep.nexusai.cache.AiCache(java.time.Duration.ofMinutes(1), 10),
                prompt -> java.util.concurrent.CompletableFuture.completedFuture("x"),
                config,
                java.util.logging.Logger.getLogger("format-key"));
        assertFalse(client.cacheKey("gpt-4o-mini", "same", "chat").equals(client.cacheKey("gpt-4o-mini", "same", "hologram")));
        assertTrue(client.cacheKey("gpt-4o-mini", "same").contains("simple"));
    }

    private static String enforce(String preset, String raw) {
        FormatPreset spec = FormatPresets.builtin(preset);
        return FormatEnforcer.enforce(raw, spec);
    }
}
