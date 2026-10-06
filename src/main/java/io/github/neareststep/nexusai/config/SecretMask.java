package io.github.neareststep.nexusai.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets so logs, status, and errors can name a key without revealing it.
 * Shows the last four characters, or {@code ****} when the secret is too short for a suffix.
 * <p>
 * After each configured secret is replaced, a token that has the shape of an OpenAI ({@code sk-}),
 * Groq ({@code gsk_}), or Google ({@code AIza}) key is masked the same way, even when that key is
 * not in the config. The shape requires the vendor's length, a digit, and both letter cases in the
 * secret body, and it skips a run of ordinary capitalized words. A lowercase slug, a word, and a
 * token that is only one case stay as written. Callers include NPC replies, placeholder answers,
 * {@code /nai test}, {@code actions.log}, server logs, and stored dialogue lines. Configured
 * secrets are applied first and are never put back.
 */
public final class SecretMask {

    /** A key at or under this length is masked as {@code ****} and matched on a token boundary. */
    public static final int SUFFIX_LENGTH = 4;

    /**
     * OpenAI-style {@code sk-} (20 or more), Groq {@code gsk_} (20 or more), and Google {@code AIza}
     * (exactly 35). A match still has to look like a random secret: a digit and both letter cases
     * in the body, and not a run of ordinary words. {@code /} and {@code -} before the token still
     * match, so a real key in a path is not missed.
     */
    private static final Pattern VENDOR_KEY = Pattern.compile(
            "(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{35})(?![A-Za-z0-9_-])");

    /** An ordinary capitalized word, such as {@code Smithing}. */
    private static final Pattern CAMEL_WORD = Pattern.compile("[A-Z][a-z]{2,}");

    private static final Pattern CONSECUTIVE_UPPERS = Pattern.compile("[A-Z]{2}");

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
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        if (secrets != null) {
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
        }
        return maskVendorKeys(result);
    }

    private static String maskVendorKeys(String text) {
        Matcher matcher = VENDOR_KEY.matcher(text);
        StringBuilder masked = null;
        int cursor = 0;
        while (matcher.find()) {
            String token = matcher.group();
            if (!looksLikeVendorSecret(token)) {
                continue;
            }
            if (masked == null) {
                masked = new StringBuilder();
            }
            masked.append(text, cursor, matcher.start());
            masked.append(mask(token));
            cursor = matcher.end();
        }
        if (masked == null) {
            return text;
        }
        masked.append(text, cursor, text.length());
        return masked.toString();
    }

    /**
     * The body must contain a digit and both letter cases. A lowercase slug, an all-uppercase
     * identifier, and a short sequence of capitalized words are left alone. Alternating case,
     * as in a real provider key, is not that sequence.
     */
    private static boolean looksLikeVendorSecret(String token) {
        String body = vendorBody(token);
        if (body == null || !containsDigit(body) || !containsUpper(body) || !containsLower(body)) {
            return false;
        }
        return !looksLikeWords(body);
    }

    private static String vendorBody(String token) {
        if (token.startsWith("sk-")) {
            return token.substring(3);
        }
        if (token.startsWith("gsk_")) {
            return token.substring(4);
        }
        if (token.startsWith("AIza")) {
            return token.substring(4);
        }
        return null;
    }

    /**
     * True for text such as {@code ReadTheSmithingGuide}, which has several capitalized words and
     * few capitals. A provider key mixes case throughout and fails this check.
     */
    private static boolean looksLikeWords(String body) {
        if (body.indexOf('_') >= 0 || CONSECUTIVE_UPPERS.matcher(body).find()) {
            return false;
        }
        int words = 0;
        Matcher matcher = CAMEL_WORD.matcher(body);
        while (matcher.find()) {
            words++;
        }
        if (words < 3) {
            return false;
        }
        int letters = 0;
        int uppers = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppers++;
                }
            }
        }
        return letters > 0 && uppers * 100 <= letters * 34;
    }

    private static boolean containsDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsUpper(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isUpperCase(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsLower(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLowerCase(token.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String tokenPattern(String secret) {
        return "(?<![\\p{Alnum}_-])" + Pattern.quote(secret) + "(?![\\p{Alnum}_-])";
    }
}
