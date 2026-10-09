package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Saved files compared with {@link YamlConfiguration}. The event scan may refuse a file.
 * A scan that returns keys must return the same set, and a stop append must not
 * rename, drop, or duplicate a character.
 */
class SavedMemoryScanFuzzTest {

    private static final String[] FRAG = {
            ": ", "#", " #x", "'", "\"", "- ", "\n", "\t", "{", "}", "[", "]", "&a", "*b", "!t", "|", ">", "Кир", "😀",
            "a:b", "ns:npc", "", " ", "  trailing  ", "123", "true", "null", "~", "?", "%", "@", "`", ",", "\\",
            "\u2028", "\u0085", "\r", "x", "npc_1", "yes", "0x1F", "012", "on", "-", ":", "\\n", "\u00a0", "\ufeff",
            "\u0007", "\u00e9", "é", "ẞ", "\uD83D\uDE00\uD83D\uDE00", "=", "<<", "---", "..."
    };
    private static final String[] WHOLE = {
            "true", "false", "yes", "no", "on", "off", "null", "~", "123", "-5", "1e3", "0x1F", "012", "1_000", "<<",
            "=", "-", "?", "---", "...", "npc", "ns:npc", "a:b:c", "http://x/y", "key: v", "# c", "'q'", "\"d\"",
            "- x", "[a]", "{a: b}", "&anc", "*ali", "!tag", "|", ">", " lead", "trail ", "multi\nline", "tab\there",
            "😀", "Кирилл", "", "x".repeat(200), "a: b ".repeat(40), "\u2028", "\u0085x", "é\u0301", "%x", "@x", "`x"
    };
    private static final String[] REALISTIC = {
            "yes", "no", "on", "off", "true", "false", "null", "y", "n", "007", "012", "0x1f", "1e3", "1_000", "123",
            "-5", "0b101", "npc", "guard", "ns:npc", "myplugin:guard-1", "a:b", "x:007", "nan", "inf", "-", "_", "--",
            "1-2", "0o7", "ns:yes"
    };

    @Test
    void savedFilesMatchOrAreRefused() throws Exception {
        int appendBefore = appendBad;
        String chaotic = run(1L, 400, false);
        int chaoticAppendBad = appendBad - appendBefore;
        String realistic = run(1L, 500, true);
        System.out.println(chaotic);
        System.out.println(realistic);
        assertEquals(0, mismatches, "chaotic\n" + chaotic + "\nrealistic\n" + realistic);
        assertEquals(0, unparseable, chaotic + realistic);
        assertEquals(0, appendBad - appendBefore - chaoticAppendBad, realistic);
    }

    private int mismatches;
    private int unparseable;
    private int appendBad;
    private int scanOk;
    private int rejected;
    private int appended;
    private int fallback;
    private int summariesMade;
    private final StringBuilder log = new StringBuilder();
    private Random random;

    private String run(long seed, int files, boolean realistic) throws Exception {
        random = new Random(seed);
        Logger logger = Logger.getLogger("memory-scan");
        logger.setUseParentHandlers(false);
        Path dir = Files.createTempDirectory("memory-scan");
        int mismatchesBefore = mismatches;
        int unparseableBefore = unparseable;
        int appendBadBefore = appendBad;
        for (int fileIndex = 0; fileIndex < files; fileIndex++) {
            boolean summaries = random.nextBoolean();
            Path file = dir.resolve("m" + fileIndex + ".yml");
            MemoryStore source = new MemoryStore();
            List<UUID> players = new ArrayList<>();
            int playerCount = 1 + random.nextInt(4);
            for (int p = 0; p < playerCount; p++) {
                UUID player = UUID.randomUUID();
                players.add(player);
                int characterCount = 1 + random.nextInt(4);
                for (int c = 0; c < characterCount; c++) {
                    String characterId = id(realistic);
                    int lineCount = 1 + random.nextInt(4);
                    for (int line = 0; line < lineCount; line++) {
                        source.append(player, characterId, line % 2 == 0 ? "user" : "assistant", text(),
                                70L, 64, 1_000_000, 0L);
                    }
                    if (summaries && random.nextBoolean()) {
                        trySummary(source, player, characterId);
                    }
                }
            }
            source.save(file.toFile(), logger, summaries);
            String before = Files.readString(file, StandardCharsets.UTF_8);
            compare("f" + fileIndex + " saved", before);
            compare("f" + fileIndex + " crlf", before.replace("\n", "\r\n"));
            compare("f" + fileIndex + " bom", "\ufeff" + before);

            Set<String> originalKeys;
            try {
                originalKeys = yamlKeys(before);
            } catch (RuntimeException | org.bukkit.configuration.InvalidConfigurationException e) {
                continue;
            }
            YamlConfiguration original = new YamlConfiguration();
            original.loadFromString(before);
            for (int round = 0; round < 2; round++) {
                String current = Files.readString(file, StandardCharsets.UTF_8);
                Set<String> currentKeys = yamlKeys(current);
                MemoryStore store = new MemoryStore();
                List<String> expectNew = new ArrayList<>();
                int fresh = 1 + random.nextInt(3);
                for (int i = 0; i < fresh; i++) {
                    UUID player = random.nextBoolean() ? players.get(random.nextInt(players.size())) : UUID.randomUUID();
                    if (!players.contains(player)) {
                        players.add(player);
                    }
                    String characterId = id(realistic);
                    if (!bukitCanStore(characterId)) {
                        continue;
                    }
                    store.append(player, characterId, "user", "NEWTEXT " + text(), 70L, 64, 1_000_000, 0L);
                    String key = player + "\u0000" + characterId;
                    if (!currentKeys.contains(key) && !expectNew.contains(key)) {
                        expectNew.add(key);
                    }
                }
                String some = currentKeys.iterator().next();
                String[] parts = some.split("\u0000", 2);
                store.append(UUID.fromString(parts[0]), parts[1], "user", "MUST-NOT-OVERWRITE", 70L, 64, 1_000_000, 0L);
                long fallbackBefore = MemoryStore.fullDocumentAppends.get();
                try {
                    store.appendCharactersAbsentFromFile(file.toFile(), logger, summaries, List.of("sk-secretvalue123"), null);
                } catch (RuntimeException e) {
                    appendBad++;
                    log.append("f").append(fileIndex).append(" r").append(round).append(" append threw ").append(e).append('\n');
                    break;
                }
                if (MemoryStore.fullDocumentAppends.get() != fallbackBefore) {
                    fallback++;
                }
                String after = Files.readString(file, StandardCharsets.UTF_8);
                compare("f" + fileIndex + " r" + round + " appended", after);
                List<String> problems = new ArrayList<>();
                Map<String, List<String>> raw;
                try {
                    raw = rawKeys(after, problems);
                } catch (RuntimeException e) {
                    appendBad++;
                    log.append("f").append(fileIndex).append(" r").append(round).append(" unreadable ").append(e).append('\n');
                    break;
                }
                Map<String, Integer> count = new HashMap<>();
                for (var entry : raw.entrySet()) {
                    for (String character : entry.getValue()) {
                        count.merge(entry.getKey() + "\u0000" + character, 1, Integer::sum);
                    }
                }
                Set<String> afterKeys;
                try {
                    afterKeys = yamlKeys(after);
                } catch (Exception e) {
                    appendBad++;
                    log.append("f").append(fileIndex).append(" r").append(round).append(" bukkit unreadable ").append(e).append('\n');
                    break;
                }
                List<String> bad = new ArrayList<>(problems);
                for (var entry : count.entrySet()) {
                    if (entry.getValue() > 1) {
                        bad.add("duplicate " + show(entry.getKey()));
                    }
                }
                for (String key : expectNew) {
                    if (!afterKeys.contains(key)) {
                        bad.add("new missing " + show(key));
                    }
                }
                Set<String> rawBefore = rawKeySet(current);
                Set<String> rawAfter = rawKeySet(after);
                if (!rawAfter.containsAll(rawBefore)) {
                    bad.add("old scalar lost");
                }
                if (after.contains("MUST-NOT-OVERWRITE")) {
                    bad.add("overwrote existing");
                }
                if (after.contains("sk-secretvalue123")) {
                    bad.add("secret unmasked");
                }
                if (!expectNew.isEmpty() && !afterKeys.containsAll(expectNew)) {
                    bad.add("not written");
                }
                YamlConfiguration loaded = new YamlConfiguration();
                loaded.loadFromString(after);
                for (String key : originalKeys) {
                    String[] pair = key.split("\u0000", 2);
                    ConfigurationSection oldPlayer = original.getConfigurationSection("entries." + pair[0]);
                    ConfigurationSection newPlayer = loaded.getConfigurationSection("entries." + pair[0]);
                    if (oldPlayer == null || newPlayer == null) {
                        bad.add("player section lost");
                        continue;
                    }
                    if (!yamlEquivalent(stable(oldPlayer.get(pair[1])), stable(newPlayer.get(pair[1])))) {
                        bad.add("old changed " + show(key));
                    }
                }
                if (!bad.isEmpty()) {
                    appendBad++;
                    if (appendBad - appendBadBefore <= 8) {
                        log.append("f").append(fileIndex).append(" r").append(round)
                                .append(" summaries=").append(summaries)
                                .append(" fallback=").append(MemoryStore.fullDocumentAppends.get() - fallbackBefore)
                                .append(' ').append(bad).append('\n');
                    }
                    break;
                }
                appended++;
            }
        }
        return "seed=" + seed
                + " files=" + files
                + " realistic=" + realistic
                + " scanOk=" + scanOk
                + " rejected=" + rejected
                + " mismatch=" + (mismatches - mismatchesBefore)
                + " unparseableButAccepted=" + (unparseable - unparseableBefore)
                + " appendRounds=" + appended
                + " appendBad=" + (appendBad - appendBadBefore)
                + " fullDocumentFallback=" + fallback
                + " summaries=" + summariesMade
                + "\n" + log;
    }

    private void compare(String label, String text) throws Exception {
        Set<String> scan;
        try {
            scan = MemoryStore.scanCharacterKeys(text);
        } catch (RuntimeException e) {
            log.append(label).append(" scan threw ").append(e).append('\n');
            mismatches++;
            return;
        }
        Set<String> yaml;
        try {
            yaml = yamlKeys(text);
        } catch (Exception e) {
            if (scan != null) {
                unparseable++;
                if (unparseable <= 10) {
                    log.append(label).append(" accepted unreadable keys=").append(show(scan))
                            .append(' ').append(show(e.getMessage())).append('\n');
                }
            } else {
                rejected++;
            }
            return;
        }
        if (scan == null) {
            rejected++;
            return;
        }
        if (!scan.equals(yaml)) {
            mismatches++;
            if (mismatches <= 15) {
                Set<String> scanOnly = new HashSet<>(scan);
                scanOnly.removeAll(yaml);
                Set<String> yamlOnly = new HashSet<>(yaml);
                yamlOnly.removeAll(scan);
                log.append(label).append(" mismatch scanOnly=").append(show(scanOnly))
                        .append(" yamlOnly=").append(show(yamlOnly)).append('\n');
            }
            return;
        }
        scanOk++;
    }

    private static Set<String> yamlKeys(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return MemoryStore.characterKeys(yaml);
    }

    private static Map<String, List<String>> rawKeys(String text, List<String> problems) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        options.setCodePointLimit(Integer.MAX_VALUE);
        Node root = new Yaml(options).compose(new StringReader(text));
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!(root instanceof MappingNode mapping)) {
            return out;
        }
        Set<String> rootSeen = new HashSet<>();
        for (NodeTuple tuple : mapping.getValue()) {
            String key = ((ScalarNode) tuple.getKeyNode()).getValue();
            if (!rootSeen.add(key)) {
                problems.add("duplicate root " + key);
            }
            if (!key.equals("entries") || !(tuple.getValueNode() instanceof MappingNode entries)) {
                continue;
            }
            for (NodeTuple player : entries.getValue()) {
                String playerId = ((ScalarNode) player.getKeyNode()).getValue();
                if (out.containsKey(playerId)) {
                    problems.add("duplicate player " + playerId);
                }
                List<String> characters = out.computeIfAbsent(playerId, ignored -> new ArrayList<>());
                if (player.getValueNode() instanceof MappingNode mappingNode) {
                    for (NodeTuple character : mappingNode.getValue()) {
                        characters.add(((ScalarNode) character.getKeyNode()).getValue());
                    }
                }
            }
        }
        return out;
    }

    /** Bukkit treats {@code ==} as a serialized type, and it has no path for an empty key or a document marker. */
    private static boolean bukitCanStore(String characterId) {
        return !characterId.isEmpty()
                && !"==".equals(characterId)
                && !"...".equals(characterId)
                && !"---".equals(characterId);
    }

    private String id(boolean realistic) {
        if (realistic) {
            if (random.nextInt(2) == 0) {
                return REALISTIC[random.nextInt(REALISTIC.length)];
            }
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789_-";
            StringBuilder built = new StringBuilder();
            int length = 1 + random.nextInt(random.nextInt(8) == 0 ? 70 : 10);
            for (int i = 0; i < length; i++) {
                built.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            if (random.nextInt(3) == 0) {
                built.append(':');
                int extra = 1 + random.nextInt(8);
                for (int i = 0; i < extra; i++) {
                    built.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
            }
            return built.toString();
        }
        if (random.nextInt(3) == 0) {
            return WHOLE[random.nextInt(WHOLE.length)];
        }
        StringBuilder built = new StringBuilder();
        int pieces = 1 + random.nextInt(5);
        for (int i = 0; i < pieces; i++) {
            built.append(random.nextBoolean() ? FRAG[random.nextInt(FRAG.length)] : WHOLE[random.nextInt(WHOLE.length)]);
        }
        if (random.nextInt(10) == 0) {
            built.append("y".repeat(150));
        }
        return built.toString().replace(".", "");
    }

    private String text() {
        StringBuilder built = new StringBuilder();
        int pieces = random.nextInt(8);
        for (int i = 0; i < pieces; i++) {
            built.append(random.nextBoolean() ? FRAG[random.nextInt(FRAG.length)] : WHOLE[random.nextInt(WHOLE.length)]);
        }
        if (random.nextInt(6) == 0) {
            built.append(" lorem ipsum dolor".repeat(1 + random.nextInt(30)));
        }
        return built.toString();
    }

    private void trySummary(MemoryStore store, UUID player, String characterId) {
        for (int i = 0; i < 3; i++) {
            store.append(player, characterId, "user", "fold " + i, 70L, 1, 1_000_000, 0L, true);
            store.append(player, characterId, "assistant", "fold r" + i, 70L, 1, 1_000_000, 0L, true);
        }
        TurnMemory.Fold fold = store.claimSummary(player, characterId, 1);
        if (fold != null && store.completeSummary(player, characterId, "SUMMARY " + text() + "x", 80L, fold.epoch())) {
            summariesMade++;
        }
    }

    private static Set<String> rawKeySet(String text) {
        Set<String> keys = new HashSet<>();
        for (var entry : rawKeys(text, new ArrayList<>()).entrySet()) {
            for (String character : entry.getValue()) {
                keys.add(entry.getKey() + "\u0000" + character);
            }
        }
        return keys;
    }

    private static String stable(Object value) {
        if (value instanceof ConfigurationSection section) {
            return stable(section.getValues(true));
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (var entry : map.entrySet()) {
                parts.add(stable(entry.getKey()) + "=" + stable(entry.getValue()));
            }
            parts.sort(String::compareTo);
            return parts.toString();
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object one : list) {
                parts.add(stable(one));
            }
            return parts.toString();
        }
        if (value instanceof byte[] bytes) {
            return java.util.HexFormat.of().formatHex(bytes);
        }
        return String.valueOf(value);
    }

    /** YAML treats U+0085, U+2028, and U+2029 as line breaks, so a rewrite may store them as LF. */
    private static boolean yamlEquivalent(String left, String right) {
        return normalizeBreaks(left).equals(normalizeBreaks(right));
    }

    private static String normalizeBreaks(String value) {
        return value.replace('\u0085', '\n').replace('\u2028', '\n').replace('\u2029', '\n');
    }

    private static String show(Object value) {
        return String.valueOf(value).replace("\u0000", "|").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
