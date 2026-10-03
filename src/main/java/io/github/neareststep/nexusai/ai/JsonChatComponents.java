package io.github.neareststep.nexusai.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops Minecraft JSON chat components that carry a click, hover, or insertion,
 * and keeps the visible text. A brace run that is not that kind of component
 * (code, an emoticon, a plain {@code {"text":"Hi"}} object) is left as it was.
 */
final class JsonChatComponents {

    private static final Set<String> EVENT_KEYS = Set.of(
            "clickEvent", "hoverEvent", "click_event", "hover_event", "insertion");
    private static final Set<String> COMPONENT_KEYS = Set.of(
            "text", "extra", "translate", "keybind", "score", "selector", "nbt", "with",
            "clickEvent", "hoverEvent", "click_event", "hover_event", "insertion",
            "color", "bold", "italic", "underlined", "strikethrough", "obfuscated", "font");
    private static final int MAX_DEPTH = 64;

    private JsonChatComponents() {
    }

    static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                Node node = parseAt(text, i);
                if (node != null && node.end > i && interactiveComponent(node)) {
                    out.append(plain(node));
                    i = node.end;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static Node parseAt(String text, int start) {
        Cursor cursor = new Cursor(text, start);
        return parseValue(cursor, 0);
    }

    private static boolean interactiveComponent(Node node) {
        return looksLikeComponent(node) && containsEvent(node);
    }

    private static boolean looksLikeComponent(Node node) {
        if (node.kind == Kind.OBJECT) {
            for (String key : node.fields.keySet()) {
                if (COMPONENT_KEYS.contains(key)) {
                    return true;
                }
            }
            return false;
        }
        if (node.kind == Kind.ARRAY) {
            if (node.elements.isEmpty()) {
                return false;
            }
            for (Node element : node.elements) {
                if (element.kind != Kind.STRING && element.kind != Kind.OBJECT && element.kind != Kind.ARRAY) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean containsEvent(Node node) {
        if (node.kind == Kind.OBJECT) {
            for (Map.Entry<String, Node> entry : node.fields.entrySet()) {
                if (isEvent(entry.getKey(), entry.getValue())) {
                    return true;
                }
                if (containsEvent(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.kind == Kind.ARRAY) {
            for (Node element : node.elements) {
                if (containsEvent(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isEvent(String key, Node value) {
        if (!EVENT_KEYS.contains(key) || value == null) {
            return false;
        }
        if ("insertion".equals(key)) {
            return value.kind == Kind.STRING && !value.text.isEmpty();
        }
        return value.kind == Kind.OBJECT;
    }

    /**
     * Visible text only: {@code text}, {@code extra}, {@code with}, and string elements.
     * Event payloads, colours, and translate keys are not copied out.
     */
    private static String plain(Node node) {
        if (node.kind == Kind.STRING) {
            return node.text;
        }
        if (node.kind == Kind.ARRAY) {
            StringBuilder sb = new StringBuilder();
            for (Node element : node.elements) {
                sb.append(plain(element));
            }
            return sb.toString();
        }
        if (node.kind == Kind.OBJECT) {
            StringBuilder sb = new StringBuilder();
            Node text = node.fields.get("text");
            if (text != null && text.kind == Kind.STRING) {
                sb.append(text.text);
            }
            appendPlain(sb, node.fields.get("extra"));
            appendPlain(sb, node.fields.get("with"));
            return sb.toString();
        }
        return "";
    }

    private static void appendPlain(StringBuilder sb, Node node) {
        if (node != null) {
            sb.append(plain(node));
        }
    }

    private static Node parseValue(Cursor cursor, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        cursor.skipWs();
        if (!cursor.has()) {
            return null;
        }
        char c = cursor.peek();
        if (c == '{') {
            return parseObject(cursor, depth);
        }
        if (c == '[') {
            return parseArray(cursor, depth);
        }
        if (c == '"') {
            return parseString(cursor);
        }
        if (c == 't' || c == 'f' || c == 'n' || c == '-' || (c >= '0' && c <= '9')) {
            return parseLiteral(cursor);
        }
        return null;
    }

    private static Node parseObject(Cursor cursor, int depth) {
        cursor.i++;
        Map<String, Node> fields = new LinkedHashMap<>();
        cursor.skipWs();
        if (cursor.has() && cursor.peek() == '}') {
            cursor.i++;
            return Node.object(fields, cursor.i);
        }
        while (cursor.has()) {
            cursor.skipWs();
            if (!cursor.has() || cursor.peek() != '"') {
                return null;
            }
            Node key = parseString(cursor);
            if (key == null) {
                return null;
            }
            cursor.skipWs();
            if (!cursor.has() || cursor.peek() != ':') {
                return null;
            }
            cursor.i++;
            Node value = parseValue(cursor, depth + 1);
            if (value == null) {
                return null;
            }
            fields.put(key.text, value);
            cursor.skipWs();
            if (!cursor.has()) {
                return null;
            }
            if (cursor.peek() == '}') {
                cursor.i++;
                return Node.object(fields, cursor.i);
            }
            if (cursor.peek() != ',') {
                return null;
            }
            cursor.i++;
        }
        return null;
    }

    private static Node parseArray(Cursor cursor, int depth) {
        cursor.i++;
        List<Node> elements = new ArrayList<>();
        cursor.skipWs();
        if (cursor.has() && cursor.peek() == ']') {
            cursor.i++;
            return Node.array(elements, cursor.i);
        }
        while (cursor.has()) {
            Node value = parseValue(cursor, depth + 1);
            if (value == null) {
                return null;
            }
            elements.add(value);
            cursor.skipWs();
            if (!cursor.has()) {
                return null;
            }
            if (cursor.peek() == ']') {
                cursor.i++;
                return Node.array(elements, cursor.i);
            }
            if (cursor.peek() != ',') {
                return null;
            }
            cursor.i++;
        }
        return null;
    }

    private static Node parseString(Cursor cursor) {
        cursor.i++;
        StringBuilder sb = new StringBuilder();
        while (cursor.has()) {
            char c = cursor.peek();
            cursor.i++;
            if (c == '"') {
                return Node.string(sb.toString(), cursor.i);
            }
            if (c == '\\') {
                if (!cursor.has()) {
                    return null;
                }
                char esc = cursor.peek();
                cursor.i++;
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (cursor.i + 4 > cursor.s.length()) {
                            return null;
                        }
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(cursor.s.charAt(cursor.i++), 16);
                            if (digit < 0) {
                                return null;
                            }
                            code = (code << 4) + digit;
                        }
                        sb.append((char) code);
                    }
                    default -> {
                        return null;
                    }
                }
                continue;
            }
            if (c < 0x20) {
                return null;
            }
            sb.append(c);
        }
        return null;
    }

    private static Node parseLiteral(Cursor cursor) {
        int start = cursor.i;
        if (cursor.startsWith("true")) {
            cursor.i += 4;
            return Node.other(cursor.i);
        }
        if (cursor.startsWith("false")) {
            cursor.i += 5;
            return Node.other(cursor.i);
        }
        if (cursor.startsWith("null")) {
            cursor.i += 4;
            return Node.other(cursor.i);
        }
        if (cursor.peek() == '-') {
            cursor.i++;
        }
        if (!cursor.has() || cursor.peek() < '0' || cursor.peek() > '9') {
            cursor.i = start;
            return null;
        }
        while (cursor.has() && isNumberChar(cursor.peek())) {
            cursor.i++;
        }
        return Node.other(cursor.i);
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    private enum Kind {
        OBJECT, ARRAY, STRING, OTHER
    }

    private static final class Node {
        private final int end;
        private final Kind kind;
        private final String text;
        private final Map<String, Node> fields;
        private final List<Node> elements;

        private Node(int end, Kind kind, String text, Map<String, Node> fields, List<Node> elements) {
            this.end = end;
            this.kind = kind;
            this.text = text;
            this.fields = fields;
            this.elements = elements;
        }

        private static Node object(Map<String, Node> fields, int end) {
            return new Node(end, Kind.OBJECT, "", fields, List.of());
        }

        private static Node array(List<Node> elements, int end) {
            return new Node(end, Kind.ARRAY, "", Map.of(), elements);
        }

        private static Node string(String text, int end) {
            return new Node(end, Kind.STRING, text, Map.of(), List.of());
        }

        private static Node other(int end) {
            return new Node(end, Kind.OTHER, "", Map.of(), List.of());
        }
    }

    private static final class Cursor {
        private final String s;
        private int i;

        private Cursor(String s, int i) {
            this.s = s;
            this.i = i;
        }

        private boolean has() {
            return i < s.length();
        }

        private char peek() {
            return s.charAt(i);
        }

        private boolean startsWith(String token) {
            return s.startsWith(token, i);
        }

        private void skipWs() {
            while (i < s.length() && isWs(s.charAt(i))) {
                i++;
            }
        }
    }
}
