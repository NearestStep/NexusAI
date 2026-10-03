package io.github.neareststep.nexusai.ai;

import java.net.URI;

/**
 * Builds the chat-completions URI from a configured base URL.
 * A query string or fragment stays after the path, so {@code /v1?key=X} becomes
 * {@code /v1/chat/completions?key=X}. A path that already ends in {@code /chat/completions}
 * (trailing slash ignored) is left as that endpoint.
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
        if (path.endsWith(CHAT_COMPLETIONS)) {
            return URI.create(path + suffix);
        }
        return URI.create(path + CHAT_COMPLETIONS + suffix);
    }

    private static String stripTrailingSlashes(String value) {
        String path = value;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
