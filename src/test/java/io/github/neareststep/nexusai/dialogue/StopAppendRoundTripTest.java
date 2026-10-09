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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        Set<String> scan = MemoryStore.scanCharacterKeys(text);
        assertNotNull(scan, "plain namespace keys were refused");
        assertEquals(Set.of(
                player + "\u0000ns:npc",
                player + "\u0000myplugin:guard-1"), scan);
        assertEquals(scan, MemoryStore.characterKeys(yaml(text)));
    }

    @Test
    void plainTextWithAnApostropheOrABlockScalarMatchesTheLoadedKeys() throws Exception {
        String[][] bodies = {
                {"        text: It's fine"},
                {"        text: |-", "          What's up?", "", "          New paragraph."},
                {"        text: |-", "          say \"hi", "          there"},
                {"        text: 'It''s fine'"},
        };
        for (String[] body : bodies) {
            String text = characterFile(body);
            Set<String> scan = MemoryStore.scanCharacterKeys(text);
            assertNotNull(scan, String.join(" / ", body));
            assertEquals(MemoryStore.characterKeys(yaml(text)), scan, String.join(" / ", body));
        }
    }

    @Test
    void emptyCharacterMatchesTheLoadedKeys() throws Exception {
        String text = """
                format: 2
                entries:
                  00000000-0000-0000-0000-000000000001:
                    '+7':
                    keeper:
                      updated: 2
                """;
        Set<String> scan = MemoryStore.scanCharacterKeys(text);
        assertNotNull(scan, text);
        assertEquals(MemoryStore.characterKeys(yaml(text)), scan);
        assertFalse(scan.contains("00000000-0000-0000-0000-000000000001\u0000+7"), scan.toString());
        Logger logger = Logger.getLogger("empty-character");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("empty-character").resolve("dialogue-memory.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        MemoryStore store = new MemoryStore();
        store.append(player, "+7", "user", "kept-line", 70L, 64, 1_000_000, 0L);
        MemoryStore.fullDocumentAppends.set(0);
        assertTrue(store.appendCharactersAbsentFromFile(file.toFile(), logger, false, List.of()));
        assertEquals(0, MemoryStore.fullDocumentAppends.get(), Files.readString(file));
        String after = Files.readString(file);
        YamlConfiguration loaded = yaml(after);
        assertEquals(Set.of("+7", "keeper"), loaded.getConfigurationSection("entries." + player).getKeys(false));
        assertEquals("kept-line", firstText(loaded, player, "+7"));
        assertEquals(1, countKey(after, "+7"), after);
    }

    @Test
    void unreadableFileIsLeftUntouched() throws Exception {
        String text = """
                entries:
                  00000000-0000-0000-0000-000000000001:
                    npc:
                      updated: 1
                      lines:
                      - role: user
                          text: hello
                """;
        assertEquals(null, MemoryStore.scanCharacterKeys(text));
        Logger logger = Logger.getLogger("unreadable");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("unreadable").resolve("dialogue-memory.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        store.append(UUID.randomUUID(), "npc", "user", "new", 70L, 64, 1_000_000, 0L);
        assertFalse(store.appendCharactersAbsentFromFile(file.toFile(), logger, false, List.of()));
        assertEquals(text, Files.readString(file));
    }

    @Test
    void normalSaveMatchesAPlainConfigurationSave() throws Exception {
        UUID player = new UUID(1L, 2L);
        String line = "It's fine, say \"hi\".\nNew paragraph.";
        String summary = "Asked what's up.\nSecond line.";
        List<TurnMemory.Line> lines = List.of(
                new TurnMemory.Line("user", line),
                new TurnMemory.Line("assistant", "A long line with spaces that goes well past eighty columns so the emitter may fold it across several lines of output text here."));
        MemoryStore store = new MemoryStore();
        store.get(player, "npc").load(lines, 40L, summary, 41L);
        Path ours = Files.createTempDirectory("bytes").resolve("dialogue-memory.yml");
        store.save(ours.toFile(), null, true);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 2);
        yaml.set("entries." + player + ".npc.updated", 40L);
        List<java.util.Map<String, String>> stored = new java.util.ArrayList<>();
        for (TurnMemory.Line one : lines) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
            row.put("role", one.role());
            row.put("text", one.text());
            stored.add(row);
        }
        yaml.set("entries." + player + ".npc.lines", stored);
        yaml.set("entries." + player + ".npc.summary", summary);
        yaml.set("entries." + player + ".npc.summary-updated", 41L);
        Path plain = ours.resolveSibling("plain.yml");
        yaml.save(plain.toFile());
        assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(ours));
        assertTrue(Files.readString(ours).contains("|-"));
    }

    private static String characterFile(String[] body) {
        StringBuilder text = new StringBuilder("""
                entries:
                  00000000-0000-0000-0000-000000000001:
                    npc:
                      updated: 1
                      lines:
                      - role: user
                """);
        for (String line : body) {
            text.append(line).append('\n');
        }
        text.append("""
                    other:
                      updated: 2
                """);
        return text.toString();
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
