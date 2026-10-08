package io.github.neareststep.nexusai.budget;

import java.time.LocalDate;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code %ainexus_usage_*%} values from the in-memory ledger. No request is started.
 * Numbers have no grouping separators. A limit, remainder, or percent is empty when
 * quotas are off or that cap is 0. Token and request counts are still returned.
 */
public final class UsagePlaceholders {

    private UsagePlaceholders() {
    }

    /**
     * @param params the placeholder body after {@code ainexus_}, for example {@code usage_server_tokens}
     * @return {@code null} when {@code params} is not a usage placeholder. An unknown {@code usage_}
     *         name is empty so it is not sent to a model as a prompt.
     */
    public static String resolve(String params, UUID playerId, TokenLedger.Snapshot snap, QuotaPolicy policy) {
        if (params == null || !params.startsWith("usage_")) {
            return null;
        }
        TokenLedger.Snapshot current = snap == null ? emptySnap() : snap;
        boolean enabled = policy != null && policy.enabled();
        if (params.startsWith("usage_server_")) {
            long cap = enabled ? policy.settings().serverTokensPerDay() : 0L;
            return metric(params.substring("usage_server_".length()), current.server(), cap);
        }
        if (params.startsWith("usage_player_")) {
            if (playerId == null) {
                return "";
            }
            TokenLedger.Counts counts = current.players().getOrDefault(playerId.toString(), TokenLedger.Counts.zero());
            long cap = 0L;
            if (enabled) {
                OptionalLong limited = policy.playerTokenCap(playerId);
                if (limited.isPresent()) {
                    cap = limited.getAsLong();
                }
            }
            return metric(params.substring("usage_player_".length()), counts, cap);
        }
        if (params.startsWith("usage_consumer_tokens_")) {
            String name = params.substring("usage_consumer_tokens_".length());
            if (name.isEmpty()) {
                return "";
            }
            return Long.toString(count(current.consumers(), name).total());
        }
        if (params.startsWith("usage_consumer_requests_")) {
            String name = params.substring("usage_consumer_requests_".length());
            if (name.isEmpty()) {
                return "";
            }
            return Long.toString(count(current.consumers(), name).requests());
        }
        return "";
    }

    static String metric(String field, TokenLedger.Counts counts, long cap) {
        TokenLedger.Counts spent = counts == null ? TokenLedger.Counts.zero() : counts;
        return switch (field) {
            case "tokens" -> Long.toString(spent.total());
            case "requests" -> Long.toString(spent.requests());
            case "limit" -> cap > 0L ? Long.toString(cap) : "";
            case "remaining" -> cap > 0L ? Long.toString(Math.max(0L, cap - spent.total())) : "";
            case "percent" -> cap > 0L ? Long.toString(Math.min(100L, spent.total() * 100L / cap)) : "";
            default -> "";
        };
    }

    private static TokenLedger.Counts count(Map<String, TokenLedger.Counts> map, String key) {
        if (map == null || key == null) {
            return TokenLedger.Counts.zero();
        }
        TokenLedger.Counts counts = map.get(key);
        return counts == null ? TokenLedger.Counts.zero() : counts;
    }

    private static TokenLedger.Snapshot emptySnap() {
        return new TokenLedger.Snapshot(
                LocalDate.now(),
                TokenLedger.Counts.zero(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                java.util.List.of());
    }
}
