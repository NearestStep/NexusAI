package io.github.neareststep.nexusai.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p>
 * {@link #redactUrl(String, Iterable)} masks a provider base URL or request URI for display and logs:
 * every query-parameter value (and a bare query token with no {@code =}) becomes {@code ***},
 * userinfo becomes {@code ***@}, then {@link #redact(String, Iterable)} runs. Scheme, host, port,
 * and path stay so the line is still useful.
 * <p>
 * {@link #addUrlSecrets(String, Collection)} adds only secret-looking parts to
 * {@link PluginConfig#configuredSecrets()}, so ordinary query values such as Azure
 * {@code api-version} or Hugging Face {@code model} ids are not stripped from NPC replies.
 * A named query value is added only when the parameter name looks secret (for example
 * {@code key}, {@code api_key}, {@code token}, {@code sig}, or a name ending in
 * {@code key}/{@code token}/{@code secret}). Values of non-secret names are never added
 * (they are still shown as {@code ***} in a masked URL). A bare query token with no {@code =},
 * and the userinfo user, are added only when they look random: no {@code /} or {@code .}, and
 * either mixed case plus digits or length at least 24. The userinfo password is always added.
 * Values under {@link #URL_SECRET_MIN_LENGTH} characters are skipped for name-based matches, and
 * common words such as {@code true} / {@code json} are skipped.
 */
public final class SecretMask {

    /** A key at or under this length is masked as {@code ****} and matched on a token boundary. */
    public static final int SUFFIX_LENGTH = 4;

    /**
     * Minimum length for a query value matched by a secret-looking parameter name before it is
     * added to configured secrets. Shorter secret-named values are masked only in the displayed URL.
     */
    public static final int URL_SECRET_MIN_LENGTH = 6;

    /** Minimum length for an all-one-case bare token treated as random. */
    private static final int RANDOM_URL_SECRET_LONG_LENGTH = 24;

    /**
     * Parameter names (after lowercasing and stripping {@code -} / {@code _}) that mark the value
     * as a configured secret.
     */
    private static final Set<String> SECRET_QUERY_NAMES = Set.of(
            "key",
            "apikey",
            "xapikey",
            "token",
            "accesstoken",
            "auth",
            "authorization",
            "secret",
            "clientsecret",
            "password",
            "pass",
            "pwd",
            "sig",
            "signature",
            "code",
            "session",
            "credential");

    /**
     * Common query words that are not treated as configured secrets even when long enough.
     * Matching is case-insensitive.
     */
    private static final Set<String> TRIVIAL_URL_SECRETS = Set.of(
            "true", "false", "null", "json", "http", "https");

    /** Trailing punctuation that must not be swallowed when a URL sits inside text. */
    private static final String TRAILING_URL_PUNCT = ".,;:)]}\"\'";

    /** An {@code http(s)://} URL that may sit inside an exception message. */
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /** Userinfo ahead of a host when the URI cannot be parsed. */
    private static final Pattern USERINFO_TEXT = Pattern.compile(
            "(?<=://)([^/@:\\s]+(?::[^/@\\s]*)?)@");

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

    /**
     * Masks query-parameter values and userinfo in a provider URL or request URI, then applies
     * {@link #redact(String, Iterable)}. Scheme, host, port, and path are kept. A key-like segment
     * in the path is not removed by this step. When {@code value} is not a single parseable URI,
     * each embedded {@code http(s)://} URL is masked the same way, and an unparseable URL falls
     * back to text masking of userinfo and query values.
     */
    public static String redactUrl(String value, Iterable<String> secrets) {
        return redact(maskUrl(value), secrets);
    }

    /**
     * Adds secret-looking query values (raw and percent-decoded) and userinfo parts from
     * {@code url} into {@code secrets}. Display masking of every query value stays in
     * {@link #redactUrl(String, Iterable)}; this method only decides what {@link #redact} may
     * strip from ordinary text. See the class javadoc for the name-based and random-value rules.
     */
    public static void addUrlSecrets(String url, Collection<String> secrets) {
        if (url == null || url.isBlank() || secrets == null) {
            return;
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            collectUserInfoSecrets(uri.getRawUserInfo(), secrets);
            collectQuerySecrets(uri.getRawQuery(), secrets);
            return;
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Fall through to text extraction.
        }
        Matcher userinfo = USERINFO_TEXT.matcher(trimmed);
        if (userinfo.find()) {
            collectUserInfoSecrets(userinfo.group(1), secrets);
        }
        int queryAt = trimmed.indexOf('?');
        if (queryAt >= 0) {
            int frag = trimmed.indexOf('#', queryAt + 1);
            String query = frag < 0 ? trimmed.substring(queryAt + 1) : trimmed.substring(queryAt + 1, frag);
            collectQuerySecrets(query, secrets);
        }
    }

    static String maskUrl(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String trimmed = value.trim();
        String whole = maskParsedUri(trimmed);
        if (whole != null) {
            if (trimmed.equals(value)) {
                return whole;
            }
            int at = value.indexOf(trimmed);
            if (at >= 0) {
                return value.substring(0, at) + whole + value.substring(at + trimmed.length());
            }
            return whole;
        }
        Matcher matcher = HTTP_URL.matcher(value);
        StringBuilder rebuilt = null;
        int pos = 0;
        while (matcher.find()) {
            if (rebuilt == null) {
                rebuilt = new StringBuilder();
            }
            rebuilt.append(value, pos, matcher.start());
            String matched = matcher.group();
            int keep = urlEndWithoutTrailingPunct(matched);
            rebuilt.append(maskUriOrText(matched.substring(0, keep)));
            rebuilt.append(matched, keep, matched.length());
            pos = matcher.end();
        }
        if (rebuilt == null) {
            return value;
        }
        rebuilt.append(value, pos, value.length());
        return rebuilt.toString();
    }

    private static int urlEndWithoutTrailingPunct(String matched) {
        int end = matched.length();
        while (end > 0 && TRAILING_URL_PUNCT.indexOf(matched.charAt(end - 1)) >= 0) {
            end--;
        }
        return end;
    }

    private static String maskUriOrText(String candidate) {
        String parsed = maskParsedUri(candidate);
        return parsed != null ? parsed : maskUrlByText(candidate);
    }

    private static String maskParsedUri(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.indexOf(':') < 0) {
            return null;
        }
        try {
            URI uri = new URI(candidate);
            if (uri.getScheme() == null) {
                return null;
            }
            if (uri.getHost() == null && uri.getRawAuthority() == null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null) {
                return null;
            }
            return rebuildMaskedUri(uri);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Builds {@code scheme://[***@]host[:port]rawPath[?maskedQuery][#rawFragment]} without
     * re-encoding the path or fragment.
     */
    private static String rebuildMaskedUri(URI uri) {
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme()).append("://");
        if (uri.getRawUserInfo() != null) {
            out.append("***@");
        }
        String host = uri.getHost();
        if (host != null) {
            String literal = host;
            if (literal.startsWith("[") && literal.endsWith("]") && literal.length() > 2) {
                literal = literal.substring(1, literal.length() - 1);
            }
            if (literal.indexOf(':') >= 0) {
                out.append('[').append(literal).append(']');
            } else {
                out.append(literal);
            }
        } else if (uri.getRawAuthority() != null) {
            String authority = uri.getRawAuthority();
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                out.append("***@").append(authority, at + 1, authority.length());
            } else {
                out.append(authority);
            }
        }
        if (uri.getPort() >= 0) {
            out.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            out.append(uri.getRawPath());
        }
        String query = maskQuery(uri.getRawQuery());
        if (query != null) {
            out.append('?').append(query);
        }
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        return out.toString();
    }

    private static String maskQuery(String query) {
        if (query == null) {
            return null;
        }
        if (query.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(query.length());
        int from = 0;
        while (from <= query.length()) {
            int amp = query.indexOf('&', from);
            int end = amp < 0 ? query.length() : amp;
            String part = query.substring(from, end);
            int eq = part.indexOf('=');
            if (eq < 0) {
                masked.append(part.isEmpty() ? "" : "***");
            } else {
                masked.append(part, 0, eq + 1).append("***");
            }
            if (amp < 0) {
                break;
            }
            masked.append('&');
            from = amp + 1;
        }
        return masked.toString();
    }

    private static String maskUrlByText(String value) {
        String masked = USERINFO_TEXT.matcher(value).replaceAll("***@");
        int queryAt = masked.indexOf('?');
        if (queryAt < 0) {
            return masked;
        }
        int frag = masked.indexOf('#', queryAt + 1);
        String before = masked.substring(0, queryAt + 1);
        String query = frag < 0 ? masked.substring(queryAt + 1) : masked.substring(queryAt + 1, frag);
        String after = frag < 0 ? "" : masked.substring(frag);
        return before + maskQuery(query) + after;
    }

    private static void collectUserInfoSecrets(String userInfo, Collection<String> secrets) {
        if (userInfo == null || userInfo.isEmpty()) {
            return;
        }
        int colon = userInfo.indexOf(':');
        if (colon < 0) {
            offerIfRandom(userInfo, secrets);
            return;
        }
        offerIfRandom(userInfo.substring(0, colon), secrets);
        offerAlways(userInfo.substring(colon + 1), secrets);
    }

    private static void collectQuerySecrets(String query, Collection<String> secrets) {
        if (query == null || query.isEmpty()) {
            return;
        }
        int from = 0;
        while (from <= query.length()) {
            int amp = query.indexOf('&', from);
            int end = amp < 0 ? query.length() : amp;
            String part = query.substring(from, end);
            int eq = part.indexOf('=');
            if (eq < 0) {
                offerIfRandom(part, secrets);
            } else {
                String name = part.substring(0, eq);
                String raw = part.substring(eq + 1);
                if (secretQueryName(name)) {
                    offerNamedSecret(raw, secrets);
                }
                // Non-secret names (model, api-version, …): display still masks as ***; do not add.
            }
            if (amp < 0) {
                break;
            }
            from = amp + 1;
        }
    }

    private static boolean secretQueryName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (normalized.isEmpty()) {
            return false;
        }
        if (SECRET_QUERY_NAMES.contains(normalized)) {
            return true;
        }
        return normalized.endsWith("key") || normalized.endsWith("token") || normalized.endsWith("secret");
    }

    private static void offerNamedSecret(String raw, Collection<String> secrets) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        addIfAcceptable(raw, URL_SECRET_MIN_LENGTH, secrets);
        String decoded = percentDecode(raw);
        if (decoded != null && !decoded.equals(raw)) {
            addIfAcceptable(decoded, URL_SECRET_MIN_LENGTH, secrets);
        }
    }

    private static void offerIfRandom(String raw, Collection<String> secrets) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        if (looksRandomSecret(raw)) {
            addValue(raw, secrets);
        }
        String decoded = percentDecode(raw);
        if (decoded != null && !decoded.equals(raw) && looksRandomSecret(decoded)) {
            addValue(decoded, secrets);
        }
    }

    private static void offerAlways(String raw, Collection<String> secrets) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        addValue(raw, secrets);
        String decoded = percentDecode(raw);
        if (decoded != null && !decoded.equals(raw)) {
            addValue(decoded, secrets);
        }
    }

    private static void addIfAcceptable(String value, int minLength, Collection<String> secrets) {
        if (value == null || value.length() < minLength) {
            return;
        }
        if (TRIVIAL_URL_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            return;
        }
        addValue(value, secrets);
    }

    private static void addValue(String value, Collection<String> secrets) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!secrets.contains(value)) {
            secrets.add(value);
        }
    }

    /**
     * True for opaque bare tokens and userinfo users: no spaces, {@code /}, or {@code .}; letters
     * and digits; and either mixed case plus digits, or length at least
     * {@link #RANDOM_URL_SECRET_LONG_LENGTH}. Model ids such as {@code meta-llama/Llama-3.1-8B} or
     * {@code Qwen2.5-72B-Instruct} fail.
     */
    static boolean looksRandomSecret(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(' ') >= 0 || value.indexOf('/') >= 0 || value.indexOf('.') >= 0) {
            return false;
        }
        if (TRIVIAL_URL_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean letter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
                letter = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
                letter = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        if (!letter || !digit) {
            return false;
        }
        return (upper && lower) || value.length() >= RANDOM_URL_SECRET_LONG_LENGTH;
    }

    private static String percentDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String maskVendorKeys(String text) {
        return maskPattern(maskPattern(text, SINGLE_CASE_VENDOR, true), VENDOR_KEY, false);
    }

    private static String maskPattern(String text, Pattern pattern, boolean exact) {
        Matcher matcher = pattern.matcher(text);
        matcher.useTransparentBounds(true);
        StringBuilder masked = null;
        int pos = 0;
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
            masked.append(text, pos, start);
            masked.append(mask(token));
            pos = kept;
            if (kept < end) {
                resume(matcher, kept, text.length());
            }
        }
        if (masked == null) {
            return text;
        }
        masked.append(text, pos, text.length());
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
        int pos = end;
        while (pos > start && text.charAt(pos - 1) == '_') {
            pos--;
        }
        while (true) {
            int underscore = text.lastIndexOf('_', pos - 1);
            if (underscore <= start) {
                break;
            }
            String segment = text.substring(underscore + 1, pos);
            if (!isPathSegment(segment)) {
                break;
            }
            pos = underscore;
            while (pos > start && text.charAt(pos - 1) == '_') {
                pos--;
            }
        }
        return pos;
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
