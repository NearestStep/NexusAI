package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBookTest {

    @Test
    void oneSessionPerPlayerEndsOnTimeoutLeaveAndClose() {
        SessionBook book = new SessionBook();
        UUID player = UUID.randomUUID();
        book.open(player, "blacksmith", "world", 0, 64, 0, 4, 30, 8, 1_000L);
        book.open(player, "guard", "world", 10, 64, 10, 4, 30, 8, 2_000L);
        assertEquals("guard", book.get(player).orElseThrow().characterId());

        book.touch(player, 2_000L);
        assertTrue(book.expired(31_999L).isEmpty());
        assertTrue(book.has(player));
        assertEquals(1, book.expired(32_000L).size());
        assertFalse(book.has(player));

        book.open(player, "guard", "world", 0, 64, 0, 2, 0, 8, 5_000L);
        assertTrue(book.left(player, "world", 3, 64, 0).isEmpty());
        assertTrue(book.left(player, "world", 0, 64, 9).isPresent());
        assertFalse(book.has(player));

        book.open(player, "guard", "world", 0, 64, 0, 2, 10, 0, 6_000L);
        assertTrue(book.left(player, "nether", 100, 64, 100).isEmpty());
        assertTrue(book.close(player).isPresent());
        assertTrue(book.close(player).isEmpty());
    }

    @Test
    void repliesExhaustedIsTrackedOnTheSession() {
        SessionBook book = new SessionBook();
        UUID player = UUID.randomUUID();
        book.open(player, "blacksmith", "world", 0, 64, 0, 2, 60, 4, 0L);
        book.addReply(player);
        assertFalse(book.get(player).orElseThrow().repliesExhausted());
        book.addReply(player);
        assertTrue(book.get(player).orElseThrow().repliesExhausted());
    }
}
