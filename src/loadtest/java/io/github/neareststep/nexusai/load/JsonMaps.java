package io.github.neareststep.nexusai.load;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal JSON for the load report. The driver jar does not shade a parser. */
final class JsonMaps {

    private JsonMaps() {
    }

    static String object(Map<String, ?> fields) {
        StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(escape(entry.getKey())).append('"').append(':');
            out.append(value(entry.getValue()));
        }
        out.append('}');
        return out.toString();
    }

    static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof String text) {
            return "\"" + escape(text) + "\"";
        }
        if (raw instanceof Boolean || raw instanceof Integer || raw instanceof Long) {
            return raw.toString();
        }
        if (raw instanceof Float number) {
            return decimal(number.doubleValue());
        }
        if (raw instanceof Double number) {
            return decimal(number);
        }
        if (raw instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                out.append(value(entry.getValue()));
            }
            out.append('}');
            return out.toString();
        }
        if (raw instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(value(list.get(i)));
            }
            out.append(']');
            return out.toString();
        }
        return "\"" + escape(String.valueOf(raw)) + "\"";
    }

    private static String decimal(double number) {
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.4f", number);
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
