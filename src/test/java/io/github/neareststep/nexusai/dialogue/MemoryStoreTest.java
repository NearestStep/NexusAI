package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @Test
    void summaryRoundTripAndLegacyFileLoads() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-memory");
        Path file = dir.resolve("dialogue-memory.yml");
        String legacy = """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: hello
                      - role: assistant
                        text: hi
                """.formatted(player);
        Files.writeString(file, legacy, StandardCharsets.UTF_8);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file.toFile(), 60L, 10_000L, null);
        assertEquals(2, loaded.transcript(player, "blacksmith", 60L, 8, 100, 10_000L).size());
        assertEquals("", loaded.summary(player, "blacksmith", 60L, 10_000L));

        loaded.completeSummary(player, "blacksmith", "They said hello.", 70L, loaded.get(player, "blacksmith").epoch());
        loaded.save(file.toFile(), null, true);

        assertTrue(Files.exists(dir.resolve("dialogue-memory.yml.bak")));
        assertEquals(legacy, Files.readString(dir.resolve("dialogue-memory.yml.bak"), StandardCharsets.UTF_8));
        String upgraded = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(upgraded.contains("format:"));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(2, yaml.getInt("format"));
        assertEquals("They said hello.", yaml.getString("entries." + player + ".blacksmith.summary"));
        assertEquals(70L, yaml.getLong("entries." + player + ".blacksmith.summary-updated"));
        assertFalse(upgraded.contains("pending"));

        MemoryStore again = new MemoryStore();
        again.load(file.toFile(), 80L, 10_000L, null);
        assertEquals("They said hello.", again.summary(player, "blacksmith", 80L, 10_000L));
        assertEquals(70L, again.get(player, "blacksmith").summaryUpdatedAt());

        again.save(file.toFile(), null, true);
        long bakFiles = Files.list(dir).filter(path -> path.getFileName().toString().contains(".bak")).count();
        assertEquals(1L, bakFiles);
    }

    @Test
    void disabledSaveMatchesTheLegacyDocument() throws Exception {
        UUID player = UUID.randomUUID();
        MemoryStore store = new MemoryStore();
        store.append(player, "blacksmith", "user", "hello", 50L, 8, 100, 0L);
        store.append(player, "blacksmith", "assistant", "hi", 51L, 8, 100, 0L);
        store.get(player, "blacksmith").completeSummary("hidden", 52L, store.get(player, "blacksmith").epoch());

        Path dir = Files.createTempDirectory("dialogue-off");
        Path file = dir.resolve("dialogue-memory.yml");
        store.save(file.toFile(), null, false);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(text.contains("summary"));
        assertFalse(text.contains("format"));
        assertFalse(Files.exists(dir.resolve("dialogue-memory.yml.bak")));

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(51L, yaml.getLong("entries." + player + ".blacksmith.updated"));
        assertEquals("hello", yaml.getMapList("entries." + player + ".blacksmith.lines").get(0).get("text"));
        assertEquals("hi", yaml.getMapList("entries." + player + ".blacksmith.lines").get(1).get("text"));
        assertFalse(yaml.contains("entries." + player + ".blacksmith.summary"));
    }

    @Test
    void interruptedSaveLeavesThePreviousFileIntact() throws Exception {
        UUID player = UUID.randomUUID();
        Path dir = Files.createTempDirectory("dialogue-kill");
        Path file = dir.resolve("dialogue-memory.yml");
        String original = "entries:\n  " + player + ":\n    blacksmith:\n      updated: 1\n      lines:\n      - {role: user, text: safe}\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        MemoryStore store = new MemoryStore();
        store.append(player, "blacksmith", "user", "new", 9L, 8, 100, 0L);
        store.save(file.toFile(), null, true, (temporary, target) -> {
            throw new IOException("killed");
        });

        assertArrayEquals(before, Files.readAllBytes(file));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals("safe", yaml.getMapList("entries." + player + ".blacksmith.lines").get(0).get("text"));
        assertFalse(yaml.contains("format"));
        assertEquals(0, Files.list(dir).filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
    }
}
