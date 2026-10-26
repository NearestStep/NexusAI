package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Singular and plural forms in the dialogue memory log lines that carry a count. */
class MemoryLogTextTest {

    @Test
    void dialogueCharacterCounts() {
        assertEquals("0 dialogue characters", MemoryStore.dialogueCharacters(0));
        assertEquals("1 dialogue character", MemoryStore.dialogueCharacters(1));
        assertEquals("2 dialogue characters", MemoryStore.dialogueCharacters(2));
    }

    @Test
    void leftOutWarning() {
        assertEquals("Left 1 dialogue character out of dialogue-memory.yml because YAML could not write it; "
                + "the other characters were saved", MemoryStore.leftOutText(1));
        assertEquals("Left 2 dialogue characters out of dialogue-memory.yml because YAML could not write them; "
                + "the other characters were saved", MemoryStore.leftOutText(2));
    }

    @Test
    void staleTempFiles() {
        assertEquals("Removed 1 stale dialogue-memory.yml temp file left after a save was interrupted.",
                MemoryStore.removedTempsText(1));
        assertEquals("Removed 2 stale dialogue-memory.yml temp files left after saves were interrupted.",
                MemoryStore.removedTempsText(2));
    }

    @Test
    void stopBudgetLine() {
        assertEquals("Stop ran during the first dialogue memory load and used the stop budget: "
                        + "5000 ms for a 1 KiB dialogue-memory.yml, 3 ms used. "
                        + "1 dialogue character held in memory: appended 1, already in file 0.",
                DialogueMemoryPersistence.budgetStopLine(5_000L, 10L, 3L, 1, 0, 1));
        assertEquals("Stop ran during the first dialogue memory load and used the stop budget: "
                        + "5000 ms for a 1 KiB dialogue-memory.yml, 3 ms used. "
                        + "2 dialogue characters held in memory: appended 1, already in file 1.",
                DialogueMemoryPersistence.budgetStopLine(5_000L, 10L, 3L, 1, 1, 2));
    }
}
