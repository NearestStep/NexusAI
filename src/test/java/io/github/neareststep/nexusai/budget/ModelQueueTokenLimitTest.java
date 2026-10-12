package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelQueueTokenLimitTest {

    @Test
    void tokenCapSkipsTheRowUntilQuotasAreOff() {
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 8), Logger.getLogger("row-tokens"));
        QuotaPolicy policy = new QuotaPolicy(
                ledger, () -> LocalDate.of(2026, 10, 8), () -> 10_000L,
                Logger.getLogger("row-tokens"), List::of);
        policy.apply(new QuotaSettings(true, 0L, 0L, Map.of(), Map.of()));
        String first = "0|openai|gpt-4o-mini";
        ledger.record(
                ResponseUsage.reported(8, 4, 12, null),
                CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", ""),
                "openai",
                first,
                false);
        ModelQueue queue = queue(List.of(
                new QueueEntryConfig("openai", "gpt-4o-mini", 0, 10),
                new QueueEntryConfig("groq", "llama", 0, 0)));
        queue.rowTokens(policy);
        assertEquals(1, queue.select(10_000L).orElseThrow().index());
        assertTrue(queue.status(10_000L).getFirst().state().contains("LIMIT REACHED (tokens 12/10)"));
        assertTrue(queue.allRowsTokenBlocked(10_000L) == false);

        policy.apply(QuotaSettings.off());
        assertEquals(0, queue.select(10_000L).orElseThrow().index());
        assertEquals(0, new QueueEntryConfig("openai", "gpt-4o-mini", 3).dailyTokenLimit());
    }

    @Test
    void tokenExhaustionIsLocalQuotaAndRequestExhaustionStaysLocalLimit() {
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 8), Logger.getLogger("row-explain"));
        QuotaPolicy policy = new QuotaPolicy(
                ledger, () -> LocalDate.of(2026, 10, 8), () -> 10_000L,
                Logger.getLogger("row-explain"), List::of);
        policy.apply(new QuotaSettings(true, 0L, 0L, Map.of(), Map.of()));
        ledger.record(
                ResponseUsage.reported(10, 0, 10, null),
                CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", ""),
                "openai",
                "0|openai|small",
                false);
        ModelQueue tokens = queue(List.of(new QueueEntryConfig("openai", "small", 0, 10)));
        tokens.rowTokens(policy);
        AiRequestException tokenOnly = tokens.explain(null, 10_000L);
        assertEquals(AiErrorKind.LOCAL_QUOTA, tokenOnly.kind());
        assertTrue(tokenOnly.getMessage().contains("All model-queue entries are exhausted."));

        ModelQueue requests = queue(List.of(new QueueEntryConfig("openai", "capped", 1, 0)));
        assertTrue(requests.tryConsume(0, 10_000L));
        AiRequestException requestOnly = requests.explain(null, 10_000L);
        assertEquals(AiErrorKind.LOCAL_LIMIT, requestOnly.kind());
        assertTrue(requestOnly.getMessage().contains("All model-queue entries are exhausted."));
    }

    private static ModelQueue queue(List<QueueEntryConfig> entries) {
        return new ModelQueue(
                entries,
                0,
                60_000L,
                300_000L,
                null,
                () -> 10_000L,
                () -> LocalDate.of(2026, 10, 8),
                ZoneId.of("UTC"),
                Logger.getLogger("model-queue-token-limit"));
    }
}
