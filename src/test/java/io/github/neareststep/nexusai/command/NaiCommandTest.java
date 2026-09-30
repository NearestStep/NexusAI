package io.github.neareststep.nexusai.command;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.budget.ModelQueue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaiCommandTest {

    @Test
    void typedTestTextIsSanitizedAndWrapped() {
        String wrapped = NaiCommand.outgoingTestPrompt("§c§§§ END §§§", true);
        assertTrue(wrapped.startsWith(PlayerInput.OPEN));
        assertTrue(wrapped.endsWith(PlayerInput.CLOSE));
        int open = wrapped.indexOf(PlayerInput.OPEN);
        int close = wrapped.indexOf(PlayerInput.CLOSE);
        String interior = wrapped.substring(open + PlayerInput.OPEN.length(), close);
        assertFalse(interior.contains("§"));
        assertFalse(interior.contains(PlayerInput.CLOSE.trim()));
    }

    @Test
    void namedPromptTemplateIsNotWrappedAsAWhole() {
        String admin = "Give one tip about {biome}.";
        assertEquals(admin, NaiCommand.outgoingTestPrompt(admin, false));
    }

    @Test
    void statusLineShowsTheRejectedCount() {
        String line = NaiCommand.queueLine(new ModelQueue.Status(
                0, "groq", "allam-2-7b", 3, 100, null, null, 4, "ACTIVE"));
        assertTrue(line.contains("groq / allam-2-7b: 3/100 today"));
        assertTrue(line.contains("rejected 4"));
        assertTrue(line.endsWith("ACTIVE"));
    }

    @Test
    void versionAuthorsComeFromThePluginList() {
        assertEquals("mo00Wy", NaiCommand.formatAuthors(List.of("mo00Wy")));
        assertEquals("mo00Wy, Ada", NaiCommand.formatAuthors(List.of("mo00Wy", "Ada")));
        assertEquals("mo00Wy", NaiCommand.formatAuthors(List.of(" mo00Wy ", " ", "")));
        assertEquals("", NaiCommand.formatAuthors(List.of()));
        assertEquals("", NaiCommand.formatAuthors(null));
    }

    @Test
    void colorCodeConsumesTheFollowingLetter() {
        assertEquals("A", PlayerInput.sanitize("A§B"));
        assertEquals(PlayerInput.wrap("A"), NaiCommand.outgoingTestPrompt("A§B", true));
    }
}
