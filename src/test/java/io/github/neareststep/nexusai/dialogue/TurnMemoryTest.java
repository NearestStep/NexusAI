package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnMemoryTest {

    @Test
    void keepsTheLastTurnsThenTheCharacterCap() {
        TurnMemory memory = new TurnMemory();
        for (int i = 1; i <= 10; i++) {
            memory.add("user", "q" + i, i);
            memory.add("assistant", "a" + i, i);
        }
        memory.trim(8, 10_000);
        assertEquals(8, memory.userTurns());
        assertEquals("q3", memory.view().get(0).text());
        assertEquals("a10", memory.view().get(memory.view().size() - 1).text());

        memory.trim(8, 6);
        assertTrue(memory.chars() <= 6);
        assertTrue(memory.userTurns() <= 8);
    }

    @Test
    void expiryDropsTheTranscript() {
        TurnMemory memory = new TurnMemory();
        memory.add("user", "hello", 1_000L);
        memory.expire(1_000L + 3_600_000L - 1, 3_600_000L);
        assertEquals(1, memory.view().size());
        memory.expire(1_000L + 3_600_000L, 3_600_000L);
        assertTrue(memory.view().isEmpty());
        memory.expire(9_000L, 0L);
        memory.add("user", "still", 9_000L);
        memory.expire(99_000L, 0L);
        assertEquals(1, memory.view().size());
    }

    @Test
    void droppedLinesWaitInTheFoldBuffer() {
        TurnMemory memory = new TurnMemory();
        for (int i = 1; i <= 4; i++) {
            memory.add("user", "q" + i, i);
            memory.add("assistant", "a" + i, i);
        }
        memory.trim(2, 10_000, true);
        assertEquals(2, memory.userTurns());
        assertEquals("q3", memory.view().get(0).text());
        assertEquals(2, memory.pendingUserTurns());
        assertEquals("q1", memory.pendingView().get(0).text());
        assertEquals("a2", memory.pendingView().get(memory.pendingView().size() - 1).text());

        memory.trim(2, 10_000, false);
        assertEquals(2, memory.pendingUserTurns());
        assertEquals("q3", memory.view().get(0).text());
    }

    @Test
    void expireClearsTheSummary() {
        TurnMemory memory = new TurnMemory();
        memory.load(java.util.List.of(new TurnMemory.Line("user", "hello")), 1_000L, "Earlier facts.", 1_000L);
        memory.trim(2, 100, true);
        memory.expire(1_000L + 3_600_000L - 1, 3_600_000L);
        assertEquals("Earlier facts.", memory.summary());
        memory.expire(1_000L + 3_600_000L, 3_600_000L);
        assertEquals("", memory.summary());
        assertEquals(0L, memory.summaryUpdatedAt());
        assertTrue(memory.view().isEmpty());
        assertTrue(memory.pendingView().isEmpty());
        assertFalse(memory.summaryInFlight());
    }

    @Test
    void storeRoundTripKeepsPlayerAndCharacter() throws Exception {
        MemoryStore store = new MemoryStore();
        UUID player = UUID.randomUUID();
        store.append(player, "blacksmith", "user", "hello", 50L, 8, 100, 0L);
        store.append(player, "blacksmith", "assistant", "hi", 51L, 8, 100, 0L);
        java.io.File file = java.nio.file.Files.createTempFile("dialogue-memory", ".yml").toFile();
        store.save(file, null);

        MemoryStore loaded = new MemoryStore();
        loaded.load(file, 52L, 10_000L, null);
        assertEquals(2, loaded.transcript(player, "blacksmith", 52L, 8, 100, 10_000L).size());
        MemoryStore expired = new MemoryStore();
        expired.load(file, 52L + 10_000L, 10_000L, null);
        assertTrue(expired.transcript(player, "blacksmith", 52L + 10_000L, 8, 100, 10_000L).isEmpty());
    }
}
