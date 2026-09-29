package io.github.neareststep.nexusai.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code x-ratelimit-remaining-*} and {@code x-ratelimit-reset-*} the way OpenAI and Groq send them,
 * plus {@code Retry-After} as delta seconds.
 */
public final class RateLimitHeaders {

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)");

    private RateLimitHeaders() {
    }

    public record Snapshot(Long remainingRequests, Long remainingTokens, Long resetAtMillis) {

        public boolean exhausted(int threshold) {
            return (remainingRequests != null && remainingRequests <= threshold)
                    || (remainingTokens != null && remainingTokens <= threshold);
        }
    }

    public static Snapshot parse(Map<String, List<String>> headers, long nowMillis) {
        if (headers == null || headers.isEmpty()) {
            return new Snapshot(null, null, null);
        }
        Long remainingRequests = firstLong(headers, "x-ratelimit-remaining-requests");
        Long remainingTokens = firstLong(headers, "x-ratelimit-remaining-tokens");
        Long resetRequests = parseReset(first(headers, "x-ratelimit-reset-requests"), nowMillis);
        Long resetTokens = parseReset(first(headers, "x-ratelimit-reset-tokens"), nowMillis);
        Long retryAfter = parseRetryAfter(first(headers, "retry-after"), nowMillis);
        Long resetAt = latest(resetRequests, resetTokens, retryAfter);
        return new Snapshot(remainingRequests, remainingTokens, resetAt);
    }

    /**
     * Reset instant for the buckets that are at or under the threshold. Falls back to any known reset.
     */
    public static Long resetForExhausted(Map<String, List<String>> headers, long nowMillis, int threshold) {
        if (headers == null) {
            return null;
        }
        Long remainingRequests = firstLong(headers, "x-ratelimit-remaining-requests");
        Long remainingTokens = firstLong(headers, "x-ratelimit-remaining-tokens");
        Long resetRequests = parseReset(first(headers, "x-ratelimit-reset-requests"), nowMillis);
        Long resetTokens = parseReset(first(headers, "x-ratelimit-reset-tokens"), nowMillis);
        Long retryAfter = parseRetryAfter(first(headers, "retry-after"), nowMillis);
        Long relevant = null;
        if (remainingRequests != null && remainingRequests <= threshold) {
            relevant = latest(relevant, resetRequests);
        }
        if (remainingTokens != null && remainingTokens <= threshold) {
            relevant = latest(relevant, resetTokens);
        }
        relevant = latest(relevant, retryAfter);
        if (relevant == null) {
            relevant = latest(resetRequests, resetTokens);
        }
        return relevant;
    }

    static Long parseReset(String raw, long nowMillis) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.chars().anyMatch(Character::isLetter)) {
            Long delta = parseDurationMillis(text);
            return delta == null ? null : nowMillis + delta;
        }
        try {
            if (text.contains(".")) {
                double seconds = Double.parseDouble(text);
                return nowMillis + (long) (seconds * 1000.0d);
            }
            long number = Long.parseLong(text);
            if (number >= 1_000_000_000_000L) {
                return number;
            }
            if (number >= 1_000_000_000L) {
                return number * 1000L;
            }
            return nowMillis + number * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long parseRetryAfter(String raw, long nowMillis) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return nowMillis + Math.max(0L, seconds) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseDurationMillis(String text) {
        Matcher matcher = DURATION.matcher(text.replace(" ", ""));
        long total = 0L;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            double amount = Double.parseDouble(matcher.group(1));
            total += switch (matcher.group(2)) {
                case "h" -> (long) (amount * 3_600_000L);
                case "m" -> (long) (amount * 60_000L);
                case "ms" -> (long) amount;
                default -> (long) (amount * 1_000L);
            };
        }
        return found ? total : null;
    }

    private static Long latest(Long... values) {
        Long best = null;
        for (Long value : values) {
            if (value == null) {
                continue;
            }
            if (best == null || value > best) {
                best = value;
            }
        }
        return best;
    }

    private static String first(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values == null || values.isEmpty() || values.getFirst() == null) {
                    return null;
                }
                return values.getFirst();
            }
        }
        return null;
    }

    private static Long firstLong(Map<String, List<String>> headers, String name) {
        String raw = first(headers, name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
