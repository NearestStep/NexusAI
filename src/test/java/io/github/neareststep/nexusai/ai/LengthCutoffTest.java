package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

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
    void onlyLengthMarksACutOffReply() {
        assertTrue(LengthCutoff.isLength("length"));
        assertTrue(LengthCutoff.isLength(" Length "));
        assertFalse(LengthCutoff.isLength("stop"));
        assertFalse(LengthCutoff.isLength(null));
    }

    @Test
    void aListNumberAtLineStartIsNotASentence() {
        assertEquals("Bring a few planks.", LengthCutoff.trim("Bring a few planks.\n3."));
        assertEquals("Bring a few planks.", LengthCutoff.trim("Bring a few planks.\n3. oak"));
        assertEquals("Bring a few planks.", LengthCutoff.trim("Bring a few planks.\n  12."));
        assertEquals("Bring a few planks.", LengthCutoff.trim("Bring a few planks.\r\n3. spruce"));
        assertEquals("The count stopped at 3.", LengthCutoff.trim("The count stopped at 3. Then cu"));
    }

    @Test
    void abbreviationsAreNotSentenceEnds() {
        assertEquals("Please ask…", LengthCutoff.trim("Please ask Mr."));
        assertEquals("Please ask…", LengthCutoff.trim("Please ask MRS."));
        assertEquals("Please ask…", LengthCutoff.trim("Please ask Dr."));
        assertEquals("Please ask…", LengthCutoff.trim("Please ask St."));
        assertEquals("Please ask…", LengthCutoff.trim("Please ask vs."));
        assertEquals("Compare…", LengthCutoff.trim("Compare e.g."));
        assertEquals("Compare…", LengthCutoff.trim("Compare i.e."));
        assertEquals("Compare…", LengthCutoff.trim("Compare etc."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри т.д."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри т.п."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри т.е."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри др."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри пр."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри г."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри гг."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри им."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри ул."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри см."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри напр."));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри (т.д.)"));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри (etc.)"));
        assertEquals("Смотри…", LengthCutoff.trim("Смотри (e.g.)"));
        assertEquals("Смотри (т.д.).", LengthCutoff.trim("Смотри (т.д.)."));
        assertEquals("Ask Mr. Smith about the dock.", LengthCutoff.trim("Ask Mr. Smith about the dock. Then cu"));
        assertEquals("First.", LengthCutoff.trim("First."));
        assertEquals("See 1st.", LengthCutoff.trim("See 1st."));
        assertEquals("Это флаг.", LengthCutoff.trim("Это флаг."));

        String late = "xxxxxxxxxxxxxxxxxxxx (и т.д.) tail words cut";
        String trimmed = LengthCutoff.trim(late);
        assertTrue(trimmed.contains("tail words"));
        assertFalse(trimmed.endsWith("т.д.)"));
    }
}
