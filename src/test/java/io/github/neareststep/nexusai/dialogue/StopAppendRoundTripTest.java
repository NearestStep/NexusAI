package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopAppendRoundTripTest {

    @Test
    void scanKeepsAColonInsideAPlainKey() throws Exception {
        UUID player = UUID.randomUUID();
        String text = """
                entries:
                  %s:
                    ns:npc:
                      updated: 1
                      lines:
                      - role: user
                        text: OLD-LINE
                    myplugin:guard-1:
                      updated: 1
                      lines:
                      - role: user
                        text: kept
                """.formatted(player);
        SavedMemoryScan scan = SavedMemoryScan.scan(text);
        assertNotNull(scan, "plain namespace keys were refused");
        assertEquals(Set.of(
                player + "\u0000ns:npc",
                player + "\u0000myplugin:guard-1"), scan.characterKeys);
        assertEquals(scan.characterKeys, MemoryStore.characterKeys(yaml(text)));
    }

    @Test
    void scanRefusesAPlainKeyYamlWouldNotKeepAsAString() {
        String text = """
                entries:
                  player:
                    on:
                      updated: 1
                """;
        assertEquals(null, SavedMemoryScan.scan(text));
    }

    @Test
    void stopKeepsNamespacedCharactersAndAppendsSpecialIds() throws Exception {
        stopAppend("ns:npc", "other", "hi");
        stopAppend("myplugin:guard-1", "other", "hi");
        for (String id : List.of(
                "on", "yes", "no", "off", "null", "true",
                "007", "012", "0x1f", "1_000", "0b101", "1e3", "123")) {
            stopAppend("npc", id, "hi");
        }
    }

    @Test
    void saveAndStopKeepLineSeparatorText() throws Exception {
        for (String text : List.of(
                "line one\u2028line two",
                "line one\u0085line two",
                "a\u2029b",
                "first\nsecond\u2028third")) {
            save(text);
            stopAppend("npc", "other", text);
        }
    }

    private static void stopAppend(String existingId, String newId, String newText) throws Exception {
        Logger logger = Logger.getLogger("stop-append");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("stop-append").resolve("dialogue-memory.yml");
        UUID player = UUID.randomUUID();
        MemoryStore source = new MemoryStore();
        source.append(player, existingId, "user", "OLD-LINE", 70L, 64, 1_000_000, 0L);
        source.append(player, "base", "user", "base", 70L, 64, 1_000_000, 0L);
        source.save(file.toFile(), logger, false);
        MemoryStore store = new MemoryStore();
        store.append(player, existingId, "user", "NEW-LINE-MUST-NOT-APPEAR", 70L, 64, 1_000_000, 0L);
        store.append(player, newId, "user", newText, 70L, 64, 1_000_000, 0L);
        assertTrue(store.appendCharactersAbsentFromFile(file.toFile(), logger, false, List.of()));
        String after = Files.readString(file, StandardCharsets.UTF_8);
        YamlConfiguration yaml = yaml(after);
        ConfigurationSection characters = yaml.getConfigurationSection("entries." + player);
        assertNotNull(characters, after);
        assertTrue(characters.getKeys(false).contains(existingId), after);
        assertTrue(characters.getKeys(false).contains(newId), newId + "\n" + after);
        assertEquals("OLD-LINE", firstText(yaml, player, existingId), after);
        assertEquals(newText, firstText(yaml, player, newId), after);
        assertFalse(after.contains("NEW-LINE-MUST-NOT-APPEAR"), after);
        assertEquals(1, countKey(after, existingId), after);
    }

    private static void save(String text) throws Exception {
        Logger logger = Logger.getLogger("save-round-trip");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("save-round-trip").resolve("dialogue-memory.yml");
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "npc", "user", text, 70L, 64, 1_000_000, 0L);
        store.save(file.toFile(), logger, false);
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        YamlConfiguration loaded = yaml(saved);
        assertEquals("npc", loaded.getConfigurationSection("entries." + player).getKeys(false).iterator().next());
        assertEquals(text, firstText(loaded, player, "npc"), saved);
    }

    private static int countKey(String text, String id) {
        String plain = id + ":";
        String quoted = "'" + id.replace("'", "''") + "':";
        int found = 0;
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(plain) || trimmed.equals(quoted)) {
                found++;
            }
        }
        return found;
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(text);
        return parsed;
    }

    private static String firstText(YamlConfiguration parsed, UUID player, String character) {
        List<java.util.Map<?, ?>> lines = parsed.getMapList("entries." + player + "." + character + ".lines");
        assertFalse(lines.isEmpty(), character);
        return String.valueOf(lines.get(0).get("text"));
    }
}
