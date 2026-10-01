package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LengthCutoffTest {

    @Test
    void sentenceInTheLatterHalfIsKept() {
        String text = "The harbor is quiet today. Ships wait at the dock. Then the tide suddenly cu";
        assertEquals("The harbor is quiet today. Ships wait at the dock.", LengthCutoff.trim(text));
        assertEquals("The harbor is quiet.", LengthCutoff.trim("The harbor is quiet."));
        assertEquals("He said \"Go.\"", LengthCutoff.trim("He said \"Go.\""));
    }

    @Test
    void earlySentenceFallsBackToTheLastWholeWord() {
        assertEquals(
                "Hi. The traveler walked toward the…",
                LengthCutoff.trim("Hi. The traveler walked toward the mount"));
        assertEquals("The quick brown fox…", LengthCutoff.trim("The quick brown fox jum"));
        assertEquals("Supercalifragilistic…", LengthCutoff.trim("Supercalifragilistic"));
    }

    @Test
    void ellipsisCjkAndArabicMarksCountWhenTheyAreLateEnough() {
        assertEquals(
                "The old road ends here…",
                LengthCutoff.trim("The old road ends here… and the cart tu"));
        assertEquals(
                "The travelers waited...",
                LengthCutoff.trim("The travelers waited... then it cu"));
        assertEquals("今日は晴れです。", LengthCutoff.trim("今日は晴れです。明日も続くでしょ"));
        assertEquals("هل وصلت؟", LengthCutoff.trim("هل وصلت؟ ثم"));
    }

    @Test
    void aDecimalPointIsNotASentence() {
        assertEquals(
                "Pi is 3.14 and the rest of this reply is cut off at the…",
                LengthCutoff.trim("Pi is 3.14 and the rest of this reply is cut off at the en"));
    }

    @Test
    void colourCodesAreRemovedBeforeTheCut() {
        String trimmed = LengthCutoff.trim("Hello &cworld. The traveler walked toward the mount");
        assertEquals("Hello world. The traveler walked toward the…", trimmed);
        assertFalse(trimmed.contains("&"));
        assertFalse(trimmed.contains("§"));
        assertEquals("", LengthCutoff.trim("&c§l"));
    }

    @Test
    void onlyLengthSelectsTheShortCacheTtl() {
        assertTrue(LengthCutoff.isLength("length"));
        assertTrue(LengthCutoff.isLength(" Length "));
        assertFalse(LengthCutoff.isLength("stop"));
        assertFalse(LengthCutoff.isLength(null));
        assertEquals(Duration.ofSeconds(30), LengthCutoff.CACHE_TTL);
        assertTrue(LengthCutoff.CACHE_TTL.compareTo(Duration.ofSeconds(300)) < 0);
    }
}
