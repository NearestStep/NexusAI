package io.github.neareststep.nexusai.ai;

import java.net.URI;
import java.util.Locale;

/**
 * Builds the chat-completions URI from a configured base URL.
 * A query string or fragment stays after the path, so {@code /v1?key=X} becomes
 * {@code /v1/chat/completions?key=X} and {@code /v1#frag} becomes
 * {@code /v1/chat/completions#frag}. The fragment is part of the {@link URI}. HTTP does not
 * send it, so a server log of the request line will not show {@code #frag}. A path that already
 * contains {@code /chat/completions} as a segment (any letter case, trailing slash ignored,
 * including {@code /chat/completions/extra}) is left as that endpoint.
 */
public final class ChatEndpoints {

    private static final String CHAT_COMPLETIONS = "/chat/completions";

    private ChatEndpoints() {
    }

    public static URI chatCompletions(String root) {
        String trimmed = root == null ? "" : root.trim();
        int cut = trimmed.length();
        int query = trimmed.indexOf('?');
        int fragment = trimmed.indexOf('#');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        String path = stripTrailingSlashes(trimmed.substring(0, cut));
        String suffix = trimmed.substring(cut);
        if (containsChatCompletions(path)) {
            return URI.create(path + suffix);
        }
        return URI.create(path + CHAT_COMPLETIONS + suffix);
    }

    /**
     * True when {@code path} already has a {@code chat}/{@code completions} segment, in any case.
     * {@code /chat/completions/extra} counts. {@code /chat/completions-extra} does not.
     */
    private static boolean containsChatCompletions(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(CHAT_COMPLETIONS, from);
            if (at < 0) {
                return false;
            }
            int end = at + CHAT_COMPLETIONS.length();
            if (end == lower.length() || lower.charAt(end) == '/') {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static String stripTrailingSlashes(String value) {
        String path = value;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
