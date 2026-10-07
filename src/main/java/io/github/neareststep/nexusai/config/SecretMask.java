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
 * not in the config. The mixed-case shape requires the vendor's length, a digit, and both letter
 * cases in the secret body, and it skips a run of ordinary capitalized words, including a short
 * abbreviation such as {@code AI}, {@code MC}, or {@code API}. A lowercase slug stays as written.
 * <p>
 * Some one-case keys are masked by an exact template: DeepSeek {@code sk-} plus 32 hex digits,
 * OpenRouter {@code sk-or-v1-} plus 64 hex digits, Groq {@code gsk_} plus 52 letters or digits,
 * and a lowercase Anthropic key {@code sk-ant-api03-} or {@code sk-ant-admin01-} plus 93 characters
 * ending in {@code aa}. Any other one-case key is masked only when it is configured. A {@code _}
 * that follows a vendor key in a path is left in place. An underscore inside the key is part of
 * the key. Callers include NPC replies, placeholder answers, {@code /nai test}, {@code actions.log},
 * server logs, and stored dialogue lines. Configured secrets are applied first and are never put back.
 */
public final class SecretMask {

    /** A key at or under this length is masked as {@code ****} and matched on a token boundary. */
    public static final int SUFFIX_LENGTH = 4;

    /**
     * OpenAI-style {@code sk-} (20 or more), Groq {@code gsk_} (20 or more), and Google {@code AIza}
     * (exactly 35). A match still has to look like a random secret: a digit and both letter cases
     * in the body, and not a run of ordinary words. {@code /} and {@code -} before the token still
     * match, so a real key in a path is not missed. A {@code _} after the token is not part of it.
     */
    private static final Pattern VENDOR_KEY = Pattern.compile(
            "(?<![A-Za-z0-9])(?:sk-[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{35})(?![A-Za-z0-9_-])");

    /**
     * One-case keys the mixed-case rule misses. Length and alphabet are exact:
     * DeepSeek {@code sk-} plus 32 hex digits, OpenRouter {@code sk-or-v1-} plus 64 hex digits,
     * Groq {@code gsk_} plus 52 letters or digits (56 characters in total), and the lowercase
     * form of the gitleaks Anthropic rules {@code sk-ant-api03-[a-zA-Z0-9_\-]{93}AA} and
     * {@code sk-ant-admin01-[a-zA-Z0-9_\-]{93}AA}.
     */
    private static final Pattern SINGLE_CASE_VENDOR = Pattern.compile(
            "(?<![A-Za-z0-9])(?:"
                    + "sk-(?:[0-9a-f]{32}|[0-9A-F]{32})"
                    + "|sk-or-v1-(?:[0-9a-f]{64}|[0-9A-F]{64})"
                    + "|gsk_(?:[a-z0-9]{52}|[A-Z0-9]{52})"
                    + "|sk-ant-(?:api03|admin01)-[a-z0-9_\\-]{93}aa"
                    + ")(?![A-Za-z0-9_-])");

    /** An ordinary capitalized word, such as {@code Smithing}. */
    private static final Pattern CAMEL_WORD = Pattern.compile("[A-Z][a-z]{2,}");

    /** A path segment after a key: a lowercase word, or one or more capitalized words. */
    private static final Pattern PATH_SEGMENT = Pattern.compile("[a-z][a-z0-9-]*|(?:[A-Z][a-z]{2,})+");

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
        return maskPattern(maskPattern(text, SINGLE_CASE_VENDOR, true), VENDOR_KEY, false);
    }

    private static String maskPattern(String text, Pattern pattern, boolean exact) {
        Matcher matcher = pattern.matcher(text);
        matcher.useTransparentBounds(true);
        StringBuilder masked = null;
        int cursor = 0;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            int kept = end;
            if (!exact && text.startsWith("sk-", start)) {
                kept = trimPathTail(text, start, end);
            }
            String token = kept > start ? text.substring(start, kept) : "";
            boolean take = !token.isEmpty() && (exact || (vendorLength(token) && looksLikeVendorSecret(token)));
            if (!take) {
                if (kept < end) {
                    resume(matcher, kept, text.length());
                }
                continue;
            }
            if (masked == null) {
                masked = new StringBuilder();
            }
            masked.append(text, cursor, start);
            masked.append(mask(token));
            cursor = kept;
            if (kept < end) {
                resume(matcher, kept, text.length());
            }
        }
        if (masked == null) {
            return text;
        }
        masked.append(text, cursor, text.length());
        return masked.toString();
    }

    /**
     * {@link Matcher#region(int, int)} resets transparent bounds. Lookbehind has to see the
     * character before the resumed search, including a {@code _} that was just trimmed off.
     */
    private static void resume(Matcher matcher, int from, int end) {
        matcher.region(from, end);
        matcher.useTransparentBounds(true);
    }

    /**
     * Drops a trailing {@code _} and a following path word ({@code _plugins}, {@code _Plugin}).
     * An underscore whose next segment still looks like key material stays inside the token.
     */
    private static int trimPathTail(String text, int start, int end) {
        int cursor = end;
        while (cursor > start && text.charAt(cursor - 1) == '_') {
            cursor--;
        }
        while (true) {
            int underscore = text.lastIndexOf('_', cursor - 1);
            if (underscore <= start) {
                break;
            }
            String segment = text.substring(underscore + 1, cursor);
            if (!isPathSegment(segment)) {
                break;
            }
            cursor = underscore;
            while (cursor > start && text.charAt(cursor - 1) == '_') {
                cursor--;
            }
        }
        return cursor;
    }

    private static boolean isPathSegment(String segment) {
        return !segment.isEmpty() && !containsDigit(segment) && PATH_SEGMENT.matcher(segment).matches();
    }

    private static boolean vendorLength(String token) {
        if (token.startsWith("sk-")) {
            return token.length() >= 3 + 20;
        }
        if (token.startsWith("gsk_")) {
            return token.length() >= 4 + 20;
        }
        return token.startsWith("AIza") && token.length() == 4 + 35;
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
     * True for text such as {@code ReadTheSmithingGuide} or {@code NexusAIPluginForMinecraft},
     * which is several capitalized words and short abbreviations ({@code AI}, {@code MC}, {@code API})
     * with few capitals. A provider key mixes case throughout and fails this check.
     * An underscore still means this is not a run of words. A run of five capitals, or two capitals
     * glued to a lowercase tail ({@code AIza}), is not an abbreviation between words.
     */
    private static boolean looksLikeWords(String body) {
        if (body.indexOf('_') >= 0 || hasDisqualifyingUppers(body)) {
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
        for (int i = 0; i < body.length(); i++) {
            if (Character.isLetter(body.charAt(i))) {
                letters++;
            }
        }
        return letters > 0 && adjustedUppers(body) * 100 <= letters * 34;
    }

    /**
     * Five or more capitals in a row are not a short abbreviation. Two or more capitals followed
     * by a lowercase letter are not one either, unless the last capital starts the next word
     * ({@code AIPlugin}, {@code APIClient}, {@code MCServers}).
     */
    private static boolean hasDisqualifyingUppers(String body) {
        int i = 0;
        while (i < body.length()) {
            if (!Character.isUpperCase(body.charAt(i))) {
                i++;
                continue;
            }
            int run = 0;
            while (i + run < body.length() && Character.isUpperCase(body.charAt(i + run))) {
                run++;
            }
            if (run == 1) {
                i++;
                continue;
            }
            int acronym = boundedAcronymLength(body, i);
            if (acronym >= 2) {
                i += acronym;
                continue;
            }
            int after = i + run;
            if (run <= 4 && (after >= body.length() || Character.isDigit(body.charAt(after)))) {
                i = after;
                continue;
            }
            return true;
        }
        return false;
    }

    /** Extra capitals inside a short abbreviation count as one capital for the word ratio. */
    private static int adjustedUppers(String body) {
        int uppers = 0;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isUpperCase(body.charAt(i))) {
                uppers++;
            }
        }
        for (int i = 0; i < body.length(); i++) {
            int acronym = boundedAcronymLength(body, i);
            if (acronym >= 2) {
                uppers -= acronym - 1;
                i += acronym - 1;
            }
        }
        return uppers;
    }

    /**
     * Length of a 2–4 letter abbreviation whose next capital starts a word, such as {@code AI} in
     * {@code AIPlugin}. Returns 0 when the run is not that shape.
     */
    private static int boundedAcronymLength(String body, int index) {
        if (index >= body.length() || !Character.isUpperCase(body.charAt(index))) {
            return 0;
        }
        int run = 0;
        while (index + run < body.length() && Character.isUpperCase(body.charAt(index + run))) {
            run++;
        }
        int acronym = run - 1;
        if (acronym < 2 || acronym > 4 || !isCamelWordStart(body, index + acronym)) {
            return 0;
        }
        return acronym;
    }

    private static boolean isCamelWordStart(String body, int index) {
        if (index >= body.length() || !Character.isUpperCase(body.charAt(index))) {
            return false;
        }
        int lowers = 0;
        for (int j = index + 1; j < body.length() && Character.isLowerCase(body.charAt(j)); j++) {
            lowers++;
        }
        return lowers >= 2;
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
