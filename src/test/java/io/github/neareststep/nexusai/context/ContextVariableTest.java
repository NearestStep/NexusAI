package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextVariableTest {

    @Test
    void timeAndWeatherFormats() {
        assertEquals("morning", GameClock.format(0));
        assertEquals("morning", GameClock.format(5_999));
        assertEquals("day", GameClock.format(6_000));
        assertEquals("day", GameClock.format(11_999));
        assertEquals("evening", GameClock.format(12_000));
        assertEquals("night", GameClock.format(18_000));
        assertEquals("morning", GameClock.format(24_000));
        assertFalse(GameClock.format(1_234).contains(":"));
        assertEquals("clear", GameClock.weather(false, false));
        assertEquals("rain", GameClock.weather(true, false));
        assertEquals("thunder", GameClock.weather(true, true));
    }

    @Test
    void userVarsOverrideBuiltInsAndBuiltInsDoNotNeedPapi() {
        var prompt = PromptCatalog.parse("""
                greet:
                  prompt: "Hello {player} in {biome}"
                  vars:
                    biome: hub
                """).catalog().find("greet").orElseThrow();
        assertTrue(prompt.playerDependent());
        String rendered = prompt.render(template -> {
            throw new AssertionError(template);
        }, Map.of("player", "Steve", "biome", "plains", "weather", "rain"));
        assertEquals("Hello Steve in " + PlayerInput.wrap("hub"), rendered);
        assertTrue(PromptCatalog.parse("""
                where:
                  prompt: "You are in {world} during {time} with {weather}"
                """).catalog().find("where").orElseThrow().playerDependent());
        assertEquals("You are in {world}", ContextVariables.apply("You are in {world}", Set.of(), Map.of()));
        assertEquals("You are in lobby", ContextVariables.apply("You are in {world}", Set.of("player"), Map.of("world", "lobby")));
    }

    @Test
    void serverBuiltInsAreRawInPlaceholderAndDialoguePrompts() {
        var prompt = PromptCatalog.parse("""
                guide:
                  prompt: "standing in the {biome} biome of {world} at {time} in {weather}, talking to {player}"
                """).catalog().find("guide").orElseThrow();
        String sheet = prompt.render(template -> {
            throw new AssertionError(template);
        }, Map.of(
                "biome", "desert",
                "world", "world",
                "time", "day",
                "weather", "clear",
                "player", "QABot1"));
        assertEquals("standing in the desert biome of world at day in clear, talking to QABot1", sheet);
        assertFalse(sheet.contains("PLAYER INPUT"));
        assertFalse(sheet.contains("§"));

        String floodgate = prompt.render(template -> template, Map.of("player", ".Steve", "biome", "plains", "world", "w", "time", "night", "weather", "rain"));
        assertTrue(floodgate.contains("talking to .Steve"));
        assertFalse(floodgate.contains("PLAYER INPUT"));

        String untrusted = ContextVariables.apply("Hello {player}", Set.of(), Map.of("player", "Not A Name"));
        assertEquals("Hello " + PlayerInput.wrap("Not A Name"), untrusted);
        assertEquals("Hello " + PlayerInput.wrap("ab"), ContextVariables.apply("Hello {player}", Set.of(), Map.of("player", "ab")));
        assertEquals("Hello Steve", ContextVariables.apply("Hello {player}", Set.of(), Map.of("player", "Steve")));
    }

    @Test
    void biomeKeyUsesKeyedForEnumAndInterfaceShapes() {
        assertEquals("plains", ContextVariables.biomeKey(Biome.PLAINS));
        assertEquals("deep_ocean", ContextVariables.biomeKey(Biome.DEEP_OCEAN));
        assertEquals("cherry_grove", ContextVariables.biomeKey(new NamedBiome(NamespacedKey.minecraft("cherry_grove"))));
        assertEquals("custom_path", ContextVariables.biomeKey(new NamedBiome(new NamespacedKey("custom", "custom_path"))));
        assertEquals("", ContextVariables.biomeKey(null));
        assertEquals("", ContextVariables.biomeKey("plains"));
        assertEquals("", ContextVariables.biomeKey(new NamedBiome(null)));
        assertEquals("", ContextVariables.biomeKey(new ThrowingBiome()));
    }

    @Test
    void compiledBiomePathCallsKeyedNotBiome() throws Exception {
        byte[] bytes;
        try (InputStream in = ContextVariables.class.getResourceAsStream("ContextVariables.class")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        assertEquals(65, major, "plugin classes must be Java 21 (class file 65)");
        ClassPool pool = ClassPool.parse(bytes);
        assertFalse(pool.references("org/bukkit/block/Biome", "getKey"),
                "Biome.getKey() is invokevirtual on 1.20.6 and breaks when Biome is an interface");
        assertTrue(pool.references("org/bukkit/Keyed", "getKey"),
                "biome names must be read through Keyed.getKey()");
    }

    private static final class NamedBiome implements Keyed {
        private final NamespacedKey key;

        private NamedBiome(NamespacedKey key) {
            this.key = key;
        }

        @Override
        public NamespacedKey getKey() {
            return key;
        }
    }

    private static final class ThrowingBiome implements Keyed {
        @Override
        public NamespacedKey getKey() {
            throw new IllegalStateException("registry unavailable");
        }
    }

    /**
     * Enough of a class-file constant pool to see which methods the bytecode calls.
     */
    private static final class ClassPool {
        private final Map<Integer, String> utf8 = new HashMap<>();
        private final Map<Integer, Integer> classes = new HashMap<>();
        private final Map<Integer, int[]> nameAndType = new HashMap<>();
        private final Map<Integer, int[]> refs = new HashMap<>();

        static ClassPool parse(byte[] bytes) {
            ClassPool pool = new ClassPool();
            int count = u2(bytes, 8);
            int index = 10;
            int slot = 1;
            while (slot < count) {
                int tag = bytes[index] & 0xff;
                index++;
                switch (tag) {
                    case 1 -> {
                        int length = u2(bytes, index);
                        index += 2;
                        pool.utf8.put(slot, new String(bytes, index, length, StandardCharsets.UTF_8));
                        index += length;
                    }
                    case 7 -> {
                        pool.classes.put(slot, u2(bytes, index));
                        index += 2;
                    }
                    case 8, 16, 19, 20 -> index += 2;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> {
                        if (tag == 12) {
                            pool.nameAndType.put(slot, new int[]{u2(bytes, index), u2(bytes, index + 2)});
                        } else if (tag == 9 || tag == 10 || tag == 11) {
                            pool.refs.put(slot, new int[]{u2(bytes, index), u2(bytes, index + 2)});
                        }
                        index += 4;
                    }
                    case 5, 6 -> {
                        index += 8;
                        slot++;
                    }
                    case 15 -> index += 3;
                    default -> throw new IllegalStateException("unknown constant pool tag " + tag);
                }
                slot++;
            }
            return pool;
        }

        boolean references(String owner, String name) {
            for (int[] ref : refs.values()) {
                String refOwner = utf8.get(classes.get(ref[0]));
                int[] nat = nameAndType.get(ref[1]);
                String refName = nat == null ? null : utf8.get(nat[0]);
                if (owner.equals(refOwner) && name.equals(refName)) {
                    return true;
                }
            }
            return false;
        }

        private static int u2(byte[] bytes, int index) {
            return ((bytes[index] & 0xff) << 8) | (bytes[index + 1] & 0xff);
        }
    }
}
