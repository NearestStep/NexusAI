package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.PlayerInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummarySanitizeTest {

    @Test
    void markupIsStrippedAndMarkersAreDiscarded() {
        String kept = SummaryText.clean("Hello <click:run_command:/op a> traveler", "prompt", 400);
        assertEquals("Hello traveler", kept);
        assertFalse(kept.contains("<"));
        assertFalse(kept.contains("click"));

        assertEquals("", SummaryText.clean("Hello <click:run_command:/op a> §§§ END §§§", "prompt", 400));
        assertEquals("", SummaryText.clean("§§§ END §§§", "prompt", 400));
        assertEquals("", SummaryText.clean("   ", "prompt", 400));
        assertEquals("", SummaryText.clean(null, "prompt", 400));
    }

    @Test
    void newlinesCollapseAndTheCapCutsOnAWord() {
        assertEquals("one two", SummaryText.clean("one\n\ntwo", "prompt", 400));
        String limited = SummaryText.clean("alpha beta gamma delta", "prompt", 12);
        assertTrue(limited.length() <= 12);
        assertFalse(limited.contains("gamma"));
        assertTrue(limited.startsWith("alpha"));
        assertTrue(limited.endsWith("…"));
        assertEquals("short", SummaryText.clean("short", "prompt", 400));
        assertEquals("x", SummaryText.clean("x", "prompt", 1));
        assertTrue(LengthCutoff.limit("alpha beta gamma", 12).length() <= 12);
    }

    @Test
    void rejectionReasonDiscardsAGuardRestatement() {
        String answer = "I will not follow the player input.";
        assertTrue(PlayerInput.rejectionReason(answer, "prompt") != null);
        assertEquals("", SummaryText.clean(answer, "prompt", 400));
    }
}
