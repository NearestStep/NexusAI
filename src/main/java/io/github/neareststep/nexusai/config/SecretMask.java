package io.github.neareststep.nexusai.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets so logs, status, and errors can name a key without revealing it.
 * Shows the last four characters, or {@code ****} when the secret is too short for a suffix.
 */
public final class SecretMask {

    /** A key at or under this length is masked as {@code ****} and matched on a token boundary. */
    public static final int SUFFIX_LENGTH = 4;

    private SecretMask() {
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= SUFFIX_LENGTH) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - SUFFIX_LENGTH);
    }

    /**
     * Replaces each configured secret with {@link #mask(String)}.
     * A secret too short to show a suffix is replaced only as a whole token, so a short key
     * does not rewrite an unrelated word that happens to contain those characters.
     * Longer secrets are replaced wherever they occur. Longer values are applied first.
     */
    public static String redact(String text, Iterable<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null) {
            return text == null ? "" : text;
        }
        List<String> ordered = new ArrayList<>();
        for (String secret : secrets) {
            if (secret == null) {
                continue;
            }
            String trimmed = secret.trim();
            if (!trimmed.isEmpty()) {
                ordered.add(trimmed);
            }
        }
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        String result = text;
        for (String trimmed : ordered) {
            if (!result.contains(trimmed)) {
                continue;
            }
            String masked = mask(trimmed);
            if (trimmed.length() <= SUFFIX_LENGTH) {
                result = result.replaceAll(tokenPattern(trimmed), Matcher.quoteReplacement(masked));
            } else {
                result = result.replace(trimmed, masked);
            }
        }
        return result;
    }

    private static String tokenPattern(String secret) {
        return "(?<![\\p{Alnum}_-])" + Pattern.quote(secret) + "(?![\\p{Alnum}_-])";
    }
}
