package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.ai.ResponseUsage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the compact prompt block off the region thread from a {@link WorldContextSnapshot}.
 * Ranking of inventory and nearby totals happens here, not during capture.
 * <p>
 * Token estimate uses {@link ResponseUsage#tokensFromChars(int)} ({@code ceil(codePoints / 4)}).
 * At default {@code world-context.max-chars} (400) the body is at most
 * {@link WorldContextSettings#DOCUMENTED_MAX_BODY_TOKENS} tokens. The {@link #HEADER} line is
 * outside that body cap when the caller uses {@link #format}.
 */
public final class WorldContextFormatter {

    public static final String HEADER = "World:\n";

    private WorldContextFormatter() {
    }

    /**
     * Compact multiline block, or empty when {@code snapshot} is null. Truncates the body to
     * {@code limits.maxChars()} code points.
     */
    public static String format(WorldContextSnapshot snapshot, WorldContextSettings limits) {
        if (snapshot == null || limits == null) {
            return "";
        }
        String body = body(snapshot, limits);
        if (body.isEmpty()) {
            return "";
        }
        return HEADER + body;
    }

    /** {@code ceil(codePoints / 4)} for the whole block including the header. */
    public static int estimatedTokens(String block) {
        return ResponseUsage.tokensFromChars(ResponseUsage.chars(block));
    }

    static String body(WorldContextSnapshot snapshot, WorldContextSettings limits) {
        List<String> lines = new ArrayList<>(3);
        lines.add(playerLine(snapshot));
        String inv = countsLine("inv", topCounts(snapshot.inventoryTotals(), limits.maxInventoryItems()));
        if (!inv.isEmpty()) {
            lines.add(inv);
        }
        String near = nearbyLine(snapshot, limits);
        if (!near.isEmpty()) {
            lines.add(near);
        }
        String joined = String.join("\n", lines);
        return trim(joined, limits.maxChars());
    }

    private static String playerLine(WorldContextSnapshot snapshot) {
        StringBuilder line = new StringBuilder(64);
        line.append("p=").append(compact(snapshot.playerName()));
        line.append(" w=").append(compact(snapshot.worldName()));
        line.append(" @")
                .append(snapshot.blockX()).append(',')
                .append(snapshot.blockY()).append(',')
                .append(snapshot.blockZ());
        if (!snapshot.biome().isEmpty()) {
            line.append(" bio=").append(compact(snapshot.biome()));
        }
        return line.toString();
    }

    private static String nearbyLine(WorldContextSnapshot snapshot, WorldContextSettings limits) {
        List<WorldContextSnapshot.Count> counts = topCounts(snapshot.nearbyTotals(), limits.maxBlockTypes());
        if (counts.isEmpty()) {
            return "";
        }
        return "near=r" + snapshot.radius() + ' ' + joinCounts(counts);
    }

    private static String countsLine(String label, List<WorldContextSnapshot.Count> counts) {
        if (counts == null || counts.isEmpty()) {
            return "";
        }
        return label + '=' + joinCounts(counts);
    }

    static List<WorldContextSnapshot.Count> topCounts(Map<String, Integer> totals, int limit) {
        if (totals == null || totals.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(totals.entrySet());
        rows.sort(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        int keep = Math.min(limit, rows.size());
        List<WorldContextSnapshot.Count> out = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            Map.Entry<String, Integer> row = rows.get(i);
            out.add(new WorldContextSnapshot.Count(row.getKey(), row.getValue()));
        }
        return List.copyOf(out);
    }

    private static String joinCounts(List<WorldContextSnapshot.Count> counts) {
        StringBuilder out = new StringBuilder(counts.size() * 12);
        for (WorldContextSnapshot.Count count : counts) {
            if (count == null || count.id().isEmpty() || count.amount() <= 0) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(compact(count.id()));
            if (count.amount() != 1) {
                out.append('×').append(count.amount());
            }
        }
        return out.toString();
    }

    private static String trim(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int points = text.codePointCount(0, text.length());
        if (points <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, Math.max(0, maxChars));
        return text.substring(0, end) + "…";
    }

    /** Drops whitespace and control characters that would waste tokens or break the line. */
    private static String compact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                continue;
            }
            if (cp == '=' || cp == ',' || cp == '\n' || cp == '\r') {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }
}
