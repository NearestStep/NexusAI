package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.dialogue.ReplyChunks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Length cuts on text a player sees or the plugin stores keep surrogate pairs whole. */
class SurrogateCutTest {

    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void replyChunksDoNotSplitAnEmoji() {
        String text = "abcd" + EMOJI + "efgh" + EMOJI + EMOJI;
        for (int limit = 1; limit <= 6; limit++) {
            List<String> chunks = ReplyChunks.split(text, limit);
            assertEquals(text, String.join("", chunks), "limit " + limit);
            for (String chunk : chunks) {
                assertNoLoneSurrogate(chunk);
            }
        }
    }

    @Test
    void wrappedLinesDoNotSplitAnEmoji() {
        String text = "abcd" + EMOJI + "efgh" + EMOJI + EMOJI;
        for (int width = 1; width <= 6; width++) {
            String wrapped = FormatEnforcer.wrapLines(text, width);
            assertEquals(text, wrapped.replace("\n", ""), "width " + width);
            for (String line : wrapped.split("\n")) {
                assertNoLoneSurrogate(line);
            }
        }
    }

    @Test
    void truncatedWordsDoNotEndInHalfAnEmoji() {
        assertEquals("abcd", FormatEnforcer.truncateWords("abcd" + EMOJI + "ef", 5));
        assertEquals("abcd" + EMOJI, FormatEnforcer.truncateWords("abcd" + EMOJI + "ef", 6));
    }

    @Test
    void answerLimitDoesNotEndInHalfAnEmoji() {
        String formatted = AnswerFormatter.format("abc" + EMOJI + "defgh", false, 5, 0);
        assertNoLoneSurrogate(formatted);
        assertTrue(formatted.endsWith("…"), formatted);
    }

    static void assertNoLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
                continue;
            }
            assertFalse(Character.isSurrogate(c), "lone surrogate at " + i + " in " + text.codePoints().count());
        }
    }
}
