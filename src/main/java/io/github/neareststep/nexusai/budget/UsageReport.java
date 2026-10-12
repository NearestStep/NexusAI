package io.github.neareststep.nexusai.budget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Lines for {@code /nai usage}. Each line is a locale key plus placeholders.
 * Names are supplied by the caller so this class does not touch the server.
 */
public final class UsageReport {

    public enum View {
        SERVER, PLAYERS, CONSUMERS, HISTORY
    }

    public record Line(String key, Map<String, String> values) {
        public Line {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    private UsageReport() {
    }

    /** {@code null} argument is the server view. An unknown word is {@code null}. */
    public static View parse(String argument) {
        if (argument == null || argument.isBlank()) {
            return View.SERVER;
        }
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "players" -> View.PLAYERS;
            case "consumers" -> View.CONSUMERS;
            case "history" -> View.HISTORY;
            default -> null;
        };
    }

    public static boolean permitted(boolean command, boolean status) {
        return command && status;
    }

    public static String limitSuffix(String template, long limit) {
        if (limit <= 0L || template == null || template.isEmpty()) {
            return "";
        }
        return template.replace("{limit}", Long.toString(limit));
    }

    /** Share of today's server total that was estimated. Empty when nothing was estimated. */
    public static String estimatedSuffix(String template, long tokens, long estimated) {
        if (tokens <= 0L || estimated <= 0L || template == null || template.isEmpty()) {
            return "";
        }
        long percent = Math.min(100L, estimated * 100L / tokens);
        return template.replace("{percent}", Long.toString(percent));
    }

    public static List<Line> unknown() {
        return List.of(new Line("command.usage-unknown", Map.of()));
    }

    public static List<Line> render(
            View view,
            TokenLedger.Snapshot snap,
            String estimatedSuffix,
            Function<String, String> playerNames
    ) {
        TokenLedger.Snapshot current = snap;
        if (current == null || current.server() == null) {
            current = new TokenLedger.Snapshot(
                    java.time.LocalDate.now(), TokenLedger.Counts.zero(), Map.of(), Map.of(), Map.of(),
                    Map.of(), Map.of(), Map.of(), List.of());
        }
        View chosen = view == null ? View.SERVER : view;
        return switch (chosen) {
            case SERVER -> server(current, estimatedSuffix);
            case PLAYERS -> players(current, playerNames);
            case CONSUMERS -> consumers(current);
            case HISTORY -> history(current);
        };
    }

    private static List<Line> server(TokenLedger.Snapshot snap, String estimatedSuffix) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("command.usage-header", Map.of()));
        TokenLedger.Counts server = snap.server();
        lines.add(new Line("command.usage-server", Map.of(
                "requests", Long.toString(server.requests()),
                "tokens", Long.toString(server.total()),
                "prompt", Long.toString(server.prompt()),
                "completion", Long.toString(server.completion()),
                "estimated", estimatedSuffix == null ? "" : estimatedSuffix)));
        section(lines, "command.usage-title-providers", snap.providers(), Function.identity());
        Map<String, TokenLedger.Counts> rows = new java.util.LinkedHashMap<>();
        if (snap.rows() != null) {
            rows.putAll(snap.rows());
        }
        if (snap.fallback() != null) {
            rows.putAll(snap.fallback());
        }
        section(lines, "command.usage-title-rows", rows, Function.identity());
        section(lines, "command.usage-title-origins", snap.origins(), Function.identity());
        return List.copyOf(lines);
    }

    private static List<Line> players(TokenLedger.Snapshot snap, Function<String, String> playerNames) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("command.usage-players-header", Map.of()));
        List<Map.Entry<String, TokenLedger.Counts>> ranked = mutable(snap.players());
        ranked.sort(Comparator
                .comparingLong((Map.Entry<String, TokenLedger.Counts> entry) -> entry.getValue().total()).reversed()
                .thenComparing(Map.Entry::getKey));
        if (ranked.size() > 10) {
            ranked = ranked.subList(0, 10);
        }
        if (ranked.isEmpty()) {
            lines.add(new Line("command.usage-empty", Map.of()));
            return List.copyOf(lines);
        }
        Function<String, String> names = playerNames == null ? Function.identity() : playerNames;
        for (Map.Entry<String, TokenLedger.Counts> entry : ranked) {
            String name = names.apply(entry.getKey());
            lines.add(entryLine(name == null || name.isBlank() ? entry.getKey() : name, entry.getValue()));
        }
        return List.copyOf(lines);
    }

    private static List<Line> consumers(TokenLedger.Snapshot snap) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("command.usage-consumers-header", Map.of()));
        sectionBody(lines, snap.consumers(), Function.identity());
        return List.copyOf(lines);
    }

    private static List<Line> history(TokenLedger.Snapshot snap) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line("command.usage-history-header", Map.of()));
        List<TokenLedger.DayTotal> days = snap.history() == null ? List.of() : snap.history();
        if (days.isEmpty()) {
            lines.add(new Line("command.usage-empty", Map.of()));
            return List.copyOf(lines);
        }
        int kept = Math.min(30, days.size());
        for (int i = 0; i < kept; i++) {
            TokenLedger.DayTotal day = days.get(i);
            lines.add(new Line("command.usage-history-line", Map.of(
                    "day", day.day() == null ? "" : day.day().toString(),
                    "requests", Long.toString(day.requests()),
                    "tokens", Long.toString(day.total()))));
        }
        return List.copyOf(lines);
    }

    private static void section(
            List<Line> lines,
            String titleKey,
            Map<String, TokenLedger.Counts> counts,
            Function<String, String> names
    ) {
        lines.add(new Line(titleKey, Map.of()));
        sectionBody(lines, counts, names);
    }

    private static void sectionBody(
            List<Line> lines,
            Map<String, TokenLedger.Counts> counts,
            Function<String, String> names
    ) {
        List<Map.Entry<String, TokenLedger.Counts>> entries = sorted(counts);
        if (entries.isEmpty()) {
            lines.add(new Line("command.usage-empty", Map.of()));
            return;
        }
        for (Map.Entry<String, TokenLedger.Counts> entry : entries) {
            String name = names.apply(entry.getKey());
            lines.add(entryLine(name == null || name.isBlank() ? entry.getKey() : name, entry.getValue()));
        }
    }

    private static List<Map.Entry<String, TokenLedger.Counts>> sorted(Map<String, TokenLedger.Counts> counts) {
        if (counts == null || counts.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, TokenLedger.Counts>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        return entries;
    }

    private static List<Map.Entry<String, TokenLedger.Counts>> mutable(Map<String, TokenLedger.Counts> counts) {
        if (counts == null || counts.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(counts.entrySet());
    }

    private static Line entryLine(String name, TokenLedger.Counts counts) {
        TokenLedger.Counts spent = counts == null ? TokenLedger.Counts.zero() : counts;
        return new Line("command.usage-line", Map.of(
                "name", name,
                "requests", Long.toString(spent.requests()),
                "tokens", Long.toString(spent.total())));
    }
}
