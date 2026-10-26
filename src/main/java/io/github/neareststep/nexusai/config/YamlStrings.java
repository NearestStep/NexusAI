package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.text.Utf16;

/**
 * Double-quoted YAML scalars with no line wrapping. Newlines are escaped as {@code \n}. A lone
 * UTF-16 surrogate is written as U+FFFD: UTF-8 cannot encode it, so writing the file would fail.
 */
public final class YamlStrings {

    private YamlStrings() {
    }

    public static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (!Character.isSurrogate(c)) {
                        out.append(c);
                    } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append(c).append(text.charAt(++i));
                    } else {
                        out.append(Utf16.REPLACEMENT);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
