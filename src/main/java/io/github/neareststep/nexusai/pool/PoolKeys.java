package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.config.FormatPresets;

/**
 * Pool identity. The default {@code simple} format keeps the 0.6 key (the resolved prompt text)
 * so existing {@code pool.yml} rows still match. Any other format is part of the key.
 */
public final class PoolKeys {

    private PoolKeys() {
    }

    public static String memory(String format, String text) {
        String body = text == null ? "" : text;
        String id = FormatPresets.normalize(format);
        if (FormatPresets.SIMPLE.equals(id)) {
            return body;
        }
        return id + '\u0000' + body;
    }

    public static Parsed parse(String memory) {
        String key = memory == null ? "" : memory;
        int split = key.indexOf('\u0000');
        if (split < 0) {
            return new Parsed(FormatPresets.SIMPLE, key);
        }
        return new Parsed(key.substring(0, split), key.substring(split + 1));
    }

    public record Parsed(String format, String prompt) {
    }
}
