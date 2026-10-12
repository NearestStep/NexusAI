package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TokenAccountingTest {

    @Test
    void aQueueRowAndADedicatedFallbackUseDifferentKeys() {
        ModelQueue queue = new ModelQueue(
                List.of(
                        new io.github.neareststep.nexusai.config.QueueEntryConfig("openai", "gpt-4o-mini", 0),
                        new io.github.neareststep.nexusai.config.QueueEntryConfig("groq", "llama", 0)
                ),
                0,
                60_000L,
                300_000L,
                null,
                () -> 1_000L,
                () -> LocalDate.of(2026, 10, 7),
                ZoneId.of("UTC"),
                Logger.getLogger("accounting-queue"));
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 7), Logger.getLogger("accounting"));
        TokenLedgerStore store = new TokenLedgerStore(
                null,
                null,
                ledger,
                Logger.getLogger("accounting"),
                () -> OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, ZoneOffset.UTC),
                () -> 0L);
        TokenAccounting accounting = new TokenAccounting(store);
        UUID player = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ResponseUsage usage = ResponseUsage.reported(10, 0, 10, null);
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, player, "greet", "");
        accounting.record(usage, trace, "openai", 0, false, queue, "gpt-4o-mini");
        accounting.record(usage, trace, "groq", -1, true, queue, "llama");
        accounting.record(ResponseUsage.none(), trace, "openai", 0, false, queue, "gpt-4o-mini");
        TokenLedger.Snapshot snap = ledger.snapshot();
        assertEquals(20L, snap.server().total());
        assertEquals(2L, snap.server().requests());
        assertEquals(10L, snap.rows().get("0|openai|gpt-4o-mini").total());
        assertEquals(10L, snap.fallback().get("groq|llama").total());
        assertFalse(snap.rows().containsKey("groq|llama"));
        assertEquals(20L, snap.players().get(player.toString()).total());
    }
}
