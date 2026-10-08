package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.config.SecretMask;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageCommandTest {

    @Test
    void parseAndPermission() {
        assertEquals(UsageReport.View.SERVER, UsageReport.parse(null));
        assertEquals(UsageReport.View.SERVER, UsageReport.parse("  "));
        assertEquals(UsageReport.View.PLAYERS, UsageReport.parse("Players"));
        assertEquals(UsageReport.View.CONSUMERS, UsageReport.parse("consumers"));
        assertEquals(UsageReport.View.HISTORY, UsageReport.parse("history"));
        assertNull(UsageReport.parse("rows"));
        assertTrue(UsageReport.permitted(true, true));
        assertFalse(UsageReport.permitted(true, false));
        assertFalse(UsageReport.permitted(false, true));
        assertEquals("", UsageReport.limitSuffix(" (limit {limit})", 0));
        assertEquals(" (limit 10)", UsageReport.limitSuffix(" (limit {limit})", 10));
        assertEquals("", UsageReport.estimatedSuffix(" ({percent}% estimated)", 0, 5));
        assertEquals(" (40% estimated)", UsageReport.estimatedSuffix(" ({percent}% estimated)", 10, 4));
        assertEquals("command.usage-unknown", UsageReport.unknown().getFirst().key());
    }

    @Test
    void serverViewNamesSectionsAndEstimatedShare() {
        TokenLedger.Snapshot snap = new TokenLedger.Snapshot(
                LocalDate.of(2026, 10, 8),
                new TokenLedger.Counts(3, 10, 5, 15, 6),
                Map.of("openai", new TokenLedger.Counts(3, 10, 5, 15, 0)),
                Map.of(),
                Map.of("fallback|m", new TokenLedger.Counts(1, 1, 1, 2, 0)),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of());
        List<UsageReport.Line> lines = UsageReport.render(
                UsageReport.View.SERVER, snap, " (40% estimated)", null);
        assertEquals("command.usage-header", lines.getFirst().key());
        UsageReport.Line server = lines.get(1);
        assertEquals("command.usage-server", server.key());
        assertEquals("3", server.values().get("requests"));
        assertEquals("15", server.values().get("tokens"));
        assertEquals("10", server.values().get("prompt"));
        assertEquals("5", server.values().get("completion"));
        assertEquals(" (40% estimated)", server.values().get("estimated"));
        assertEquals("command.usage-title-providers", lines.get(2).key());
        assertEquals("openai", lines.get(3).values().get("name"));
        assertEquals("command.usage-title-rows", keyAt(lines, "command.usage-title-rows"));
        assertEquals("fallback|m", lineAfter(lines, "command.usage-title-rows").values().get("name"));
        assertEquals("command.usage-empty", lineAfter(lines, "command.usage-title-origins").key());
    }

    @Test
    void playersAreTopTenByTokensThenIdAndNamesCanBeMasked() {
        String canary = "sk-test-canary-value";
        Map<String, TokenLedger.Counts> players = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            players.put(String.format("00000000-0000-0000-0000-%012d", i),
                    new TokenLedger.Counts(1, 0, 0, i, 0));
        }
        players.put("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", new TokenLedger.Counts(1, 0, 0, 100, 0));
        players.put("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", new TokenLedger.Counts(1, 0, 0, 100, 0));
        TokenLedger.Snapshot snap = snapshot(players, Map.of(), List.of());
        List<UsageReport.Line> lines = UsageReport.render(UsageReport.View.PLAYERS, snap, "", id ->
                id.startsWith("aaaa") ? canary : id);
        assertEquals("command.usage-players-header", lines.getFirst().key());
        assertEquals(11, lines.size());
        assertEquals(canary, lines.get(1).values().get("name"));
        assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", lines.get(2).values().get("name"));
        assertEquals("100", lines.get(1).values().get("tokens"));
        String shown = SecretMask.redact(lines.get(1).values().get("name"), List.of(canary));
        assertFalse(shown.contains(canary));
        List<UsageReport.Line> empty = UsageReport.render(
                UsageReport.View.PLAYERS, snapshot(Map.of(), Map.of(), List.of()), "", null);
        assertEquals(List.of("command.usage-players-header", "command.usage-empty"),
                empty.stream().map(UsageReport.Line::key).toList());
    }

    @Test
    void consumersAreSortedAndHistoryKeepsThirtyDays() {
        Map<String, TokenLedger.Counts> consumers = new LinkedHashMap<>();
        consumers.put("Zed", new TokenLedger.Counts(1, 0, 0, 2, 0));
        consumers.put("alpha", new TokenLedger.Counts(4, 0, 0, 9, 0));
        List<UsageReport.Line> lines = UsageReport.render(
                UsageReport.View.CONSUMERS, snapshot(Map.of(), consumers, List.of()), "", null);
        assertEquals("command.usage-consumers-header", lines.getFirst().key());
        assertEquals("Zed", lines.get(1).values().get("name"));
        assertEquals("alpha", lines.get(2).values().get("name"));

        List<TokenLedger.DayTotal> days = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 10, 8);
        for (int i = 0; i < 31; i++) {
            days.add(new TokenLedger.DayTotal(start.minusDays(i), i + 1, 0, 0, (i + 1) * 10L, 0));
        }
        List<UsageReport.Line> history = UsageReport.render(
                UsageReport.View.HISTORY, snapshot(Map.of(), Map.of(), days), "", null);
        assertEquals(31, history.size());
        assertEquals("command.usage-history-header", history.getFirst().key());
        assertEquals("2026-10-08", history.get(1).values().get("day"));
        assertEquals("10", history.get(1).values().get("tokens"));
        assertEquals("2026-09-09", history.get(30).values().get("day"));
        List<UsageReport.Line> none = UsageReport.render(
                UsageReport.View.HISTORY, snapshot(Map.of(), Map.of(), List.of()), "", null);
        assertEquals("command.usage-empty", none.get(1).key());
    }

    private static String keyAt(List<UsageReport.Line> lines, String key) {
        for (UsageReport.Line line : lines) {
            if (key.equals(line.key())) {
                return line.key();
            }
        }
        return "";
    }

    private static UsageReport.Line lineAfter(List<UsageReport.Line> lines, String key) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (key.equals(lines.get(i).key())) {
                return lines.get(i + 1);
            }
        }
        throw new AssertionError(key);
    }

    private static TokenLedger.Snapshot snapshot(
            Map<String, TokenLedger.Counts> players,
            Map<String, TokenLedger.Counts> consumers,
            List<TokenLedger.DayTotal> history
    ) {
        return new TokenLedger.Snapshot(
                LocalDate.of(2026, 10, 8),
                new TokenLedger.Counts(1, 0, 0, 1, 0),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                consumers,
                players,
                history);
    }
}
