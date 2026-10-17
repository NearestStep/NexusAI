package io.github.neareststep.nexusai.dialogue;

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

class DialogueMemoryLineScanTest {

    @Test
    void handBuiltShapeMatchesEventKeysAndSplices() throws Exception {
        UUID player = UUID.fromString("071d10bc-ebb9-6239-02dc-ce241d8ca792");
        String pad = "x".repeat(40);
        StringBuilder body = new StringBuilder();
        body.append("entries:\n  ").append(player).append(":\n");
        for (int i = 0; i < 20; i++) {
            body.append("    npc_").append(i).append(":\n");
            body.append("      updated: 40\n");
            body.append("      lines:\n");
            body.append("      - role: user\n");
            body.append("        text: ").append(pad).append('\n');
        }
        String text = body.toString();
        MemoryStore.lineScans.set(0);
        MemoryStore.lineScanHits.set(0);
        MemoryStore.eventScans.set(0);
        Set<String> keys = MemoryStore.scanCharacterKeys(text);
        assertNotNull(keys);
        assertEquals(20, keys.size());
        assertTrue(MemoryStore.lineScanHits.get() > 0, "line scan missed");
        assertEquals(0, MemoryStore.eventScans.get(), "fell back to events");

        Path file = Files.createTempDirectory("line-scan").resolve("dialogue-memory.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        store.append(player, "npc_0", "user", "must-not-overwrite", 70L, 8, 8_000, 0L);
        store.append(player, "npc_new", "user", "only-in-memory", 70L, 8, 8_000, 0L);
        Logger logger = Logger.getLogger("line-scan");
        logger.setUseParentHandlers(false);
        MemoryStore.fullDocumentAppends.set(0);
        assertTrue(store.appendCharactersAbsentFromFile(file.toFile(), logger, false, List.of()));
        assertEquals(0, MemoryStore.fullDocumentAppends.get());
        String merged = Files.readString(file);
        assertTrue(merged.startsWith(text), "prefix broken");
        assertTrue(merged.contains("only-in-memory"));
        assertFalse(merged.contains("must-not-overwrite"));
    }

    @Test
    void overIndentedListItemIsRefused() {
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
    }

    @Test
    void flowMappingFallsBackToSnakeYamlEvenWhenEventsAlsoRefuseSplice() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String flow = "entries:\n  " + player + ":\n    shared: {updated: 40, lines: [{role: user, text: kept-flow}]}\n";
        MemoryStore.lineScans.set(0);
        MemoryStore.lineScanHits.set(0);
        MemoryStore.eventScans.set(0);
        assertTrue(DialogueMemoryLineScan.scan(flow).refused, "line scan should refuse flow");
        assertEquals(null, MemoryStore.scanCharacterKeys(flow), "event scan also refuses flow splice");
        assertTrue(MemoryStore.lineScans.get() > 0, "line scan was not attempted");
        assertEquals(0, MemoryStore.lineScanHits.get(), "line scan must not claim a flow hit");
        assertTrue(MemoryStore.eventScans.get() > 0, "SnakeYAML event scan did not run");
    }

    @Test
    void doubleQuotedEscapeFallsBackToSnakeYamlAndKeepsKeys() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000003");
        // Escaped quotes in a double-quoted scalar are not decoded by the line walk.
        String text = "entries:\n  " + player + ":\n    npc:\n      updated: 1\n      lines:\n"
                + "      - role: user\n        text: \"say \\\"hi\\\"\"\n";
        MemoryStore.lineScans.set(0);
        MemoryStore.lineScanHits.set(0);
        MemoryStore.eventScans.set(0);
        assertTrue(DialogueMemoryLineScan.scan(text).refused, "line scan should refuse escapes");
        Set<String> keys = MemoryStore.scanCharacterKeys(text);
        assertNotNull(keys, "event scan should outline this file");
        assertEquals(Set.of(player + "\u0000npc"), keys);
        assertTrue(MemoryStore.lineScans.get() > 0);
        assertEquals(0, MemoryStore.lineScanHits.get());
        assertTrue(MemoryStore.eventScans.get() > 0, "SnakeYAML event scan did not run");
    }

    @Test
    void anchorAliasFileFallsBackToSnakeYamlAndIsRefusedForSplice() {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000002");
        String anchored = "entries:\n  " + player + ":\n    blacksmith:\n      updated: 50\n      lines:\n"
                + "      - role: user\n        text: kept\n"
                + "zz_anchor: &a x\nzz_alias: *a\n";
        MemoryStore.lineScans.set(0);
        MemoryStore.lineScanHits.set(0);
        MemoryStore.eventScans.set(0);
        assertTrue(DialogueMemoryLineScan.scan(anchored).refused, "line scan should refuse anchors");
        assertEquals(null, MemoryStore.scanCharacterKeys(anchored), "event scan refuses splice on anchors");
        assertTrue(MemoryStore.lineScans.get() > 0);
        assertEquals(0, MemoryStore.lineScanHits.get());
        assertTrue(MemoryStore.eventScans.get() > 0, "SnakeYAML event scan did not run");
    }
}
