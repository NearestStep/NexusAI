package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplyChunksTest {

    @Test
    void shortTextStaysOneChunk() {
        assertEquals(List.of("Hello"), ReplyChunks.split("Hello", 48));
    }

    @Test
    void prefersBreakAtSpace() {
        String source = "Hello there, traveler of the road.";
        List<String> chunks = ReplyChunks.split(source, 12);
        assertTrue(chunks.size() >= 2, chunks.toString());
        assertTrue(chunks.stream().allMatch(chunk -> !chunk.isBlank()), chunks.toString());
        assertEquals(source.replace(" ", ""), String.join("", chunks).replace(" ", ""));
    }

    @Test
    void emptyAndNull() {
        assertTrue(ReplyChunks.split("", 10).isEmpty());
        assertTrue(ReplyChunks.split(null, 10).isEmpty());
    }
}
