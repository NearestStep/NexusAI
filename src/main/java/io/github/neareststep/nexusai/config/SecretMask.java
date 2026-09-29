package io.github.neareststep.nexusai.config;

/**
 * Masks secrets so logs, status, and errors can name a key without revealing it.
 * Shows the last four characters, or {@code ****} when the secret is shorter.
 */
public final class SecretMask {

    private SecretMask() {
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String trimmed = secret.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * Replaces each secret longer than four characters with {@link #mask(String)}.
     * Shorter secrets are left alone so unrelated words are not rewritten.
     */
    public static String redact(String text, Iterable<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null) {
            return text == null ? "" : text;
        }
        String result = text;
        for (String secret : secrets) {
            if (secret == null) {
                continue;
            }
            String trimmed = secret.trim();
            if (trimmed.length() <= 4 || !result.contains(trimmed)) {
                continue;
            }
            result = result.replace(trimmed, mask(trimmed));
        }
        return result;
    }
}
