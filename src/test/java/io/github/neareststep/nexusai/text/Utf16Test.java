package io.github.neareststep.nexusai.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Utf16Test {

    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void boundariesStepAroundASurrogatePair() {
        String text = "a" + EMOJI + "b";
        assertEquals(1, Utf16.boundaryBefore(text, 2));
        assertEquals(3, Utf16.boundaryAfter(text, 2));
        for (int i : new int[] {0, 1, 3, 4}) {
            assertEquals(i, Utf16.boundaryBefore(text, i));
            assertEquals(i, Utf16.boundaryAfter(text, i));
        }
        assertEquals(1, Utf16.boundaryBefore("a\uDE00", 1), "a lone low surrogate is not half of a pair");
    }

    @Test
    void headAndTailKeepPairsWhole() {
        String text = "a" + EMOJI + "b";
        assertEquals("a", Utf16.head(text, 2));
        assertEquals("a" + EMOJI, Utf16.head(text, 3));
        assertSame(text, Utf16.head(text, 4));
        assertEquals("b", Utf16.tail(text, 2));
        assertEquals(EMOJI + "b", Utf16.tail(text, 3));
        assertSame(text, Utf16.tail(text, 9));
        assertEquals("", Utf16.head(EMOJI, 1));
        assertEquals("", Utf16.tail(EMOJI, 1));
        assertEquals("", Utf16.head(null, 3));
    }

    @Test
    void loneSurrogatesBecomeReplacementCharacters() {
        assertEquals("a\uFFFDb", Utf16.replaceLoneSurrogates("a\uD800b"));
        assertEquals("a\uFFFD", Utf16.replaceLoneSurrogates("a\uD83D"));
        assertEquals("\uFFFDa", Utf16.replaceLoneSurrogates("\uDE00a"));
        assertEquals("\uFFFD\uFFFD", Utf16.replaceLoneSurrogates("\uDE00\uD83D"));
        assertEquals(EMOJI + "\uFFFD" + EMOJI, Utf16.replaceLoneSurrogates(EMOJI + "\uD83D" + EMOJI));
        assertTrue(Utf16.hasLoneSurrogate("x\uDC00"));
        assertFalse(Utf16.hasLoneSurrogate("x" + EMOJI));
        assertFalse(Utf16.hasLoneSurrogate(null));
    }

    @Test
    void textWithoutLoneSurrogatesIsTheSameInstance() {
        String plain = "hello \u00e9t\u00e9 \u043f\u0440\u0438\u0432\u0435\u0442";
        String emoji = "hi " + EMOJI + EMOJI;
        assertSame(plain, Utf16.replaceLoneSurrogates(plain));
        assertSame(emoji, Utf16.replaceLoneSurrogates(emoji));
        assertEquals(null, Utf16.replaceLoneSurrogates(null));
    }
}
