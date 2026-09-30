package io.github.neareststep.nexusai.dialogue;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueBudgetTest {

    private final ZoneId zone = ZoneId.of("UTC");

    @Test
    void cooldownAndMessageLength() {
        DialogueBudget budget = new DialogueBudget();
        UUID player = UUID.randomUUID();
        assertTrue(budget.cooldownReady(player, 1_000L, 3_000));
        budget.markMessage(player, 1_000L);
        assertFalse(budget.cooldownReady(player, 3_999L, 3_000));
        assertTrue(budget.cooldownReady(player, 4_000L, 3_000));
        assertTrue(DialogueBudget.tooLong("x".repeat(201), 200));
        assertFalse(DialogueBudget.tooLong("ok", 200));
    }

    @Test
    void conversationsResetOnTheNextLocalDay() {
        DialogueBudget budget = new DialogueBudget();
        UUID player = UUID.randomUUID();
        long day = java.time.LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli();
        assertTrue(budget.tryStartConversation(player, day, 2, zone));
        assertTrue(budget.tryStartConversation(player, day + 1_000L, 2, zone));
        assertFalse(budget.tryStartConversation(player, day + 2_000L, 2, zone));
        long next = java.time.LocalDate.of(2026, 1, 2).atStartOfDay(zone).toInstant().toEpochMilli();
        assertTrue(budget.tryStartConversation(player, next, 2, zone));
        assertTrue(budget.tryStartConversation(player, day, 0, zone));
    }
}
