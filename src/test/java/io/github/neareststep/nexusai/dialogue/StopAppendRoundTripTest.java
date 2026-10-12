package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        for (boolean summaries : new boolean[] {false, true}) {
            UUID player = new UUID(1L, 2L);
            String summary = "Asked what's up.\nSecond line.";
            List<TurnMemory.Line> lines = List.of(
                    new TurnMemory.Line("user", "It's fine, say \"hi\".\nNew paragraph."),
                    new TurnMemory.Line("assistant", "What's up?\n\nNew paragraph."),
                    new TurnMemory.Line("user", "\nleading newline"),
                    new TurnMemory.Line("assistant", "A long line with spaces that goes well past eighty columns so the emitter may fold it across several lines of output text here."));
            MemoryStore store = new MemoryStore();
            store.get(player, "npc").load(lines, 40L, summary, 41L);
            Path ours = Files.createTempDirectory("bytes").resolve("dialogue-memory.yml");
            store.save(ours.toFile(), null, summaries);
            YamlConfiguration yaml = new YamlConfiguration();
            if (summaries) {
                yaml.set("format", 2);
            }
            yaml.set("entries." + player + ".npc.updated", 40L);
            List<Map<String, String>> stored = new ArrayList<>();
            for (TurnMemory.Line one : lines) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", one.role());
                row.put("text", one.text());
                stored.add(row);
            }
            yaml.set("entries." + player + ".npc.lines", stored);
            if (summaries) {
                yaml.set("entries." + player + ".npc.summary", summary);
                yaml.set("entries." + player + ".npc.summary-updated", 41L);
            }
            Path plain = ours.resolveSibling("plain.yml");
            yaml.save(plain.toFile());
            assertArrayEquals(Files.readAllBytes(plain), Files.readAllBytes(ours), summaries ? "summaries" : "plain");
            String saved = Files.readString(ours);
            assertTrue(saved.contains("|-"), saved);
            assertTrue(saved.contains("|2-"), saved);
        }
    }

    @Test
    void paragraphTextIsAppendedAndDoesNotDropTheOtherCharacters() throws Exception {
        String[] texts = {
                "Hello there!",
                "It's fine",
                "Line one\nLine two",
                "What's up?\n\nNew paragraph.",
                "  indented start",
                "ends with space ",
                "multi\nline ends with space ",
                "x\n  indented second",
                "trailing newline\n",
                "\nleading newline",
                "tab\there"
        };
        Logger logger = Logger.getLogger("paragraph-append");
        logger.setUseParentHandlers(false);
        for (String text : texts) {
            Path file = Files.createTempDirectory("paragraph").resolve("dialogue-memory.yml");
            UUID player = new UUID(1L, 1L);
            MemoryStore base = new MemoryStore();
            base.append(player, "npc", "user", "old", 70L, 64, 1_000_000, 0L);
            base.save(file.toFile(), logger, false);
            MemoryStore store = new MemoryStore();
            store.append(player, "npc_new", "user", text, 4000L, 64, 1_000_000, 0L);
            store.append(player, "npc_plain", "user", "plain neighbour", 4000L, 64, 1_000_000, 0L);
            MemoryStore.fullDocumentAppends.set(0);
            assertTrue(appendPrepared(store, file, logger), text.replace("\n", "\\n"));
            assertEquals(0, MemoryStore.fullDocumentAppends.get(), text.replace("\n", "\\n"));
            YamlConfiguration loaded = yaml(Files.readString(file));
            assertEquals("old", firstText(loaded, player, "npc"));
            assertEquals(text, firstText(loaded, player, "npc_new"));
            assertEquals("plain neighbour", firstText(loaded, player, "npc_plain"));
        }
    }

    @Test
    void longCharacterIdStaysAtPlayerLevel() throws Exception {
        String longId = "a".repeat(130);
        String namespaced = "myplugin:" + "b".repeat(125);
        stopBeside(longId, "npc", "npc_new");
        stopBeside("npc", longId, "npc_new");
        stopBeside(namespaced, null, "npc_new");
        stopBeside("a".repeat(120), "npc", "npc_new");
    }

    @Test
    void complexKeyWithAnAdjacentCommentIsLeftUntouched() throws Exception {
        String text = Files.readString(Path.of(
                StopAppendRoundTripTest.class.getResource("/dialogue/complex-key-comment.yml").toURI()),
                StandardCharsets.UTF_8);
        assertThrows(InvalidConfigurationException.class, () -> yaml(text));
        Logger logger = Logger.getLogger("complex-comment");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("complex-comment").resolve("dialogue-memory.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        store.append(UUID.randomUUID(), "npc_new", "user", "must-not-appear", 70L, 64, 1_000_000, 0L);
        assertFalse(appendPrepared(store, file, logger));
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void readableFileTheScanRefusesIsRewritten() throws Exception {
        Logger logger = Logger.getLogger("scan-refuse");
        logger.setUseParentHandlers(false);
        UUID player = UUID.randomUUID();
        Path file = Files.createTempDirectory("scan-refuse").resolve("dialogue-memory.yml");
        String lineSeparator = "entries:\n  " + player + ":\n    npc:\n      updated: 1\n      lines:\n"
                + "      - role: user\n        text: \"a\u2028b\"\n";
        Files.writeString(file, lineSeparator, StandardCharsets.UTF_8);
        assertNotNull(yaml(lineSeparator).getConfigurationSection("entries." + player));
        assertEquals(null, MemoryStore.scanCharacterKeys(lineSeparator));
        MemoryStore store = new MemoryStore();
        store.append(player, "npc_new", "user", "added", 80L, 64, 1_000_000, 0L);
        MemoryStore.fullDocumentAppends.set(0);
        assertTrue(appendPrepared(store, file, logger));
        assertTrue(MemoryStore.fullDocumentAppends.get() > 0);
        YamlConfiguration loaded = yaml(Files.readString(file));
        assertEquals("a\u2028b", firstText(loaded, player, "npc"));
        assertEquals("added", firstText(loaded, player, "npc_new"));

        String anchored = """
                entries:
                  %s:
                    npc: &kept
                      updated: 1
                      lines:
                      - role: user
                        text: kept-anchor
                    copy: *kept
                """.formatted(player);
        Path anchorFile = file.resolveSibling("anchor.yml");
        Files.writeString(anchorFile, anchored, StandardCharsets.UTF_8);
        assertNotNull(yaml(anchored).getConfigurationSection("entries." + player));
        assertEquals(null, MemoryStore.scanCharacterKeys(anchored));
        MemoryStore anchoredStore = new MemoryStore();
        anchoredStore.append(player, "npc_new", "user", "added-anchor", 80L, 64, 1_000_000, 0L);
        assertTrue(appendPrepared(anchoredStore, anchorFile, logger));
        YamlConfiguration afterAnchor = yaml(Files.readString(anchorFile));
        assertEquals("kept-anchor", firstText(afterAnchor, player, "npc"));
        assertEquals("kept-anchor", firstText(afterAnchor, player, "copy"));
        assertEquals("added-anchor", firstText(afterAnchor, player, "npc_new"));

        String binary = """
                entries:
                  %s:
                    npc:
                      updated: 1
                      lines:
                      - role: user
                        text: !!binary aGVsbG8=
                """.formatted(player);
        Path binaryFile = file.resolveSibling("binary.yml");
        Files.writeString(binaryFile, binary, StandardCharsets.UTF_8);
        assertNotNull(yaml(binary).getConfigurationSection("entries." + player).get("npc"));
        MemoryStore binaryStore = new MemoryStore();
        binaryStore.append(player, "npc_new", "user", "added-binary", 80L, 64, 1_000_000, 0L);
        assertTrue(appendPrepared(binaryStore, binaryFile, logger));
        YamlConfiguration afterBinary = yaml(Files.readString(binaryFile));
        assertTrue(afterBinary.getConfigurationSection("entries." + player).getKeys(false).contains("npc"),
                Files.readString(binaryFile));
        assertEquals("added-binary", firstText(afterBinary, player, "npc_new"));
        Object binaryText = afterBinary.getMapList("entries." + player + ".npc.lines").get(0).get("text");
        String binaryValue = binaryText instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(binaryText);
        assertEquals("hello", binaryValue, Files.readString(binaryFile));
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

    private static boolean appendPrepared(MemoryStore store, Path file, Logger logger) {
        return store.appendCharactersAbsentFromFile(
                file.toFile(),
                logger,
                false,
                List.of(),
                MemoryStore.startAbsentScan(file.toFile()),
                System.nanoTime() + 10_000_000_000L);
    }

    private static void stopBeside(String first, String second, String fresh) throws Exception {
        Logger logger = Logger.getLogger("long-id");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("long-id").resolve("dialogue-memory.yml");
        UUID player = new UUID(1L, 1L);
        UUID other = new UUID(2L, 2L);
        YamlConfiguration written = new YamlConfiguration();
        writeOld(written, player, first, "FIRST-OLD");
        if (second != null) {
            writeOld(written, player, second, "SECOND-OLD");
        }
        written.save(file.toFile());
        MemoryStore store = new MemoryStore();
        store.append(player, fresh, "user", "NEW-LINE", 70L, 64, 1_000_000, 0L);
        store.append(other, "npc_other", "user", "OTHER-LINE", 70L, 64, 1_000_000, 0L);
        assertTrue(appendPrepared(store, file, logger), first);
        String after = Files.readString(file);
        YamlConfiguration loaded = yaml(after);
        ConfigurationSection characters = loaded.getConfigurationSection("entries." + player);
        assertNotNull(characters, after);
        assertTrue(characters.getKeys(false).contains(first), after);
        if (second != null) {
            assertTrue(characters.getKeys(false).contains(second), after);
        }
        assertTrue(characters.getKeys(false).contains(fresh), after);
        assertEquals(second == null ? 2 : 3, characters.getKeys(false).size(), after);
        assertEquals(1, countKey(after, fresh), after);
        assertEquals("NEW-LINE", firstText(loaded, player, fresh), after);
        assertEquals("OTHER-LINE", firstText(loaded, other, "npc_other"), after);
        assertEquals(keyIndentIn(after, first), keyIndentIn(after, fresh), after);
        MemoryStore reloaded = new MemoryStore();
        reloaded.load(file.toFile(), 5_000L, 0L, logger);
        assertEquals("NEW-LINE", reloaded.transcript(player, fresh, 80L, 8, 8_000, 0L).get(0).text());
        assertEquals("OTHER-LINE", reloaded.transcript(other, "npc_other", 80L, 8, 8_000, 0L).get(0).text());
        if (first.length() > 128) {
            MemoryStore saved = new MemoryStore();
            saved.append(player, first, "user", "SAVED", 70L, 64, 1_000_000, 0L);
            Path save = file.resolveSibling("saved.yml");
            saved.save(save.toFile(), logger, false);
            String body = Files.readString(save);
            assertFalse(body.contains("? "), body);
            assertTrue(yaml(body).getConfigurationSection("entries." + player).getKeys(false).contains(first), body);
        }
    }

    private static void writeOld(YamlConfiguration yaml, UUID player, String id, String text) {
        yaml.set("entries." + player + "." + id + ".updated", 1L);
        List<Map<String, String>> lines = new ArrayList<>();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", "user");
        row.put("text", text);
        lines.add(row);
        yaml.set("entries." + player + "." + id + ".lines", lines);
    }

    private static int keyIndentIn(String text, String id) {
        String plain = id + ":";
        String quoted = "'" + id.replace("'", "''") + "':";
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.equals(plain) || trimmed.equals(quoted) || trimmed.equals("? " + id)
                    || trimmed.startsWith("? " + id)) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                return indent;
            }
        }
        return -1;
    }

    private static String firstText(YamlConfiguration parsed, UUID player, String character) {
        List<java.util.Map<?, ?>> lines = parsed.getMapList("entries." + player + "." + character + ".lines");
        assertFalse(lines.isEmpty(), character);
        return String.valueOf(lines.get(0).get("text"));
    }
}
