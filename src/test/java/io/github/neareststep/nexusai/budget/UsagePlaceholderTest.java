package io.github.neareststep.nexusai.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UsagePlaceholderTest {

    @Test
    void serverMetricsFollowTheCapAndStayNumeric() {
        TokenLedger.Snapshot snap = snap(counts(2, 40, 10, 50, 0), Map.of(), Map.of());
        QuotaPolicy policy = policy(new QuotaSettings(true, 200L, 0L, Map.of(), Map.of()));
        assertEquals("50", UsagePlaceholders.resolve("usage_server_tokens", null, snap, policy));
        assertEquals("2", UsagePlaceholders.resolve("usage_server_requests", null, snap, policy));
        assertEquals("200", UsagePlaceholders.resolve("usage_server_limit", null, snap, policy));
        assertEquals("150", UsagePlaceholders.resolve("usage_server_remaining", null, snap, policy));
        assertEquals("25", UsagePlaceholders.resolve("usage_server_percent", null, snap, policy));
        assertEquals("", UsagePlaceholders.resolve("usage_server_nope", null, snap, policy));
        assertNull(UsagePlaceholders.resolve("cached_tip", null, snap, policy));
        assertEquals("1000", UsagePlaceholders.resolve(
                "usage_server_tokens", null, snap(counts(1, 0, 0, 1000, 0), Map.of(), Map.of()), policy));
    }

    @Test
    void limitFieldsAreEmptyWhenQuotasAreOffOrUnset() {
        TokenLedger.Snapshot snap = snap(counts(1, 0, 0, 50, 0), Map.of(), Map.of());
        QuotaPolicy off = policy(QuotaSettings.off());
        assertEquals("50", UsagePlaceholders.resolve("usage_server_tokens", null, snap, off));
        assertEquals("1", UsagePlaceholders.resolve("usage_server_requests", null, snap, off));
        assertEquals("", UsagePlaceholders.resolve("usage_server_limit", null, snap, off));
        assertEquals("", UsagePlaceholders.resolve("usage_server_remaining", null, snap, off));
        assertEquals("", UsagePlaceholders.resolve("usage_server_percent", null, snap, off));
        assertEquals("0", UsagePlaceholders.resolve("usage_server_tokens", null, null, null));
        assertEquals("", UsagePlaceholders.resolve("usage_server_limit", null, null, null));
    }

    @Test
    void playerValuesNeedAViewerAndUseTheGroupCap() {
        UUID player = UUID.randomUUID();
        Map<String, TokenLedger.Counts> players = new LinkedHashMap<>();
        players.put(player.toString(), counts(3, 80, 70, 150, 0));
        TokenLedger.Snapshot snap = snap(counts(3, 80, 70, 150, 0), players, Map.of());
        QuotaPolicy policy = policy(new QuotaSettings(true, 0L, 50L, Map.of(
                "vip", new QuotaSettings.GroupLimit(100L, 0L)
        ), Map.of()));
        assertEquals("", UsagePlaceholders.resolve("usage_player_tokens", null, snap, policy));
        assertEquals("150", UsagePlaceholders.resolve("usage_player_tokens", player, snap, policy));
        assertEquals("50", UsagePlaceholders.resolve("usage_player_limit", player, snap, policy));
        assertEquals("0", UsagePlaceholders.resolve("usage_player_remaining", player, snap, policy));
        assertEquals("100", UsagePlaceholders.resolve("usage_player_percent", player, snap, policy));
        assertEquals("3", UsagePlaceholders.resolve("usage_player_requests", player, snap, policy));
        policy.remember(player, List.of("vip"));
        assertEquals("100", UsagePlaceholders.resolve("usage_player_limit", player, snap, policy));
        assertEquals("0", UsagePlaceholders.resolve("usage_player_remaining", player, snap, policy));
    }

    @Test
    void consumerNamesMayContainUnderscores() {
        Map<String, TokenLedger.Counts> consumers = Map.of("my_plugin", counts(4, 0, 0, 12, 0));
        TokenLedger.Snapshot snap = snap(TokenLedger.Counts.zero(), Map.of(), consumers);
        assertEquals("12", UsagePlaceholders.resolve("usage_consumer_tokens_my_plugin", null, snap, null));
        assertEquals("4", UsagePlaceholders.resolve("usage_consumer_requests_my_plugin", null, snap, null));
        assertEquals("", UsagePlaceholders.resolve("usage_consumer_tokens_", null, snap, null));
        assertEquals("0", UsagePlaceholders.resolve("usage_consumer_requests_missing", null, snap, null));
        assertEquals("", UsagePlaceholders.resolve("usage_other", null, snap, null));
    }

    private static QuotaPolicy policy(QuotaSettings settings) {
        QuotaPolicy policy = new QuotaPolicy(
                new TokenLedger(LocalDate::now, java.util.logging.Logger.getLogger("usage-placeholder")),
                LocalDate::now,
                () -> 1_000L,
                java.util.logging.Logger.getLogger("usage-placeholder"),
                List::of);
        policy.apply(settings);
        return policy;
    }

    private static TokenLedger.Snapshot snap(
            TokenLedger.Counts server,
            Map<String, TokenLedger.Counts> players,
            Map<String, TokenLedger.Counts> consumers
    ) {
        return new TokenLedger.Snapshot(
                LocalDate.of(2026, 10, 8),
                server,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                consumers,
                players,
                List.of());
    }

    private static TokenLedger.Counts counts(long requests, long prompt, long completion, long total, long estimated) {
        return new TokenLedger.Counts(requests, prompt, completion, total, estimated);
    }
}
