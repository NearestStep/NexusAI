package io.github.neareststep.nexusai.dialogue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Player and character keys in a block-style dialogue-memory file.
 * Lines that belong to a character are skipped by indentation, so a folded or block scalar
 * is not read as another character. A flow collection, a tab, or a quoted scalar that crosses
 * back to the next key returns null and the caller uses another reader.
 * Offsets are UTF-16 indexes into the scanned text.
 */
final class SavedMemoryScan {

    final boolean format2;
    final boolean sawFormat;
    final int formatValueStart;
    final int formatValueEnd;
    final boolean sawEntries;
    final boolean otherRoot;
    /** Where a new player block is inserted, or -1 when {@code entries} was not closed. */
    final int entriesEnd;
    final int playerIndent;
    final Map<String, Integer> playerEnd;
    final Map<String, Integer> characterIndent;
    final Set<String> characterKeys;

    private SavedMemoryScan(
            boolean format2,
            boolean sawFormat,
            int formatValueStart,
            int formatValueEnd,
            boolean sawEntries,
            boolean otherRoot,
            int entriesEnd,
            int playerIndent,
            Map<String, Integer> playerEnd,
            Map<String, Integer> characterIndent,
            Set<String> characterKeys
    ) {
        this.format2 = format2;
        this.sawFormat = sawFormat;
        this.formatValueStart = formatValueStart;
        this.formatValueEnd = formatValueEnd;
        this.sawEntries = sawEntries;
        this.otherRoot = otherRoot;
        this.entriesEnd = entriesEnd;
        this.playerIndent = playerIndent;
        this.playerEnd = playerEnd;
        this.characterIndent = characterIndent;
        this.characterKeys = characterKeys;
    }

    static SavedMemoryScan scan(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new Parser(text).parse();
        } catch (Reject ignored) {
            return null;
        }
    }

    private static final class Reject extends RuntimeException {
        private Reject() {
            super(null, null, false, false);
        }
    }

    private static final class Parser {
        private final String text;
        private final int n;
        private int index;
        private boolean format2;
        private boolean sawFormat;
        private int formatValueStart = -1;
        private int formatValueEnd = -1;
        private boolean sawEntries;
        private boolean otherRoot;
        private int entriesEnd = -1;
        private int playerIndent = 2;
        private final Map<String, Integer> playerEnd = new LinkedHashMap<>();
        private final Map<String, Integer> characterIndent = new LinkedHashMap<>();
        private final Set<String> characterKeys = new java.util.HashSet<>();
        private Raw held;

        private Parser(String text) {
            this.text = text;
            this.n = text.length();
            if (n > 0 && text.charAt(0) == '\uFEFF') {
                index = 1;
            }
        }

        private SavedMemoryScan parse() {
            skipLeading();
            while (true) {
                KeyLine line = nextKey();
                if (line == null) {
                    break;
                }
                if (line.raw.indent != 0) {
                    throw new Reject();
                }
                if ("format".equals(line.key)) {
                    acceptFormat(line);
                    continue;
                }
                if ("entries".equals(line.key)) {
                    if (line.valueStart >= 0) {
                        throw new Reject();
                    }
                    sawEntries = true;
                    parseEntries();
                    continue;
                }
                otherRoot = true;
                if (line.valueStart < 0) {
                    skipIndented(line.raw.indent);
                } else if (!line.valueClosed || line.flow) {
                    throw new Reject();
                }
            }
            if (sawEntries && entriesEnd < 0) {
                entriesEnd = n;
            }
            return new SavedMemoryScan(
                    format2,
                    sawFormat,
                    formatValueStart,
                    formatValueEnd,
                    sawEntries,
                    otherRoot,
                    entriesEnd,
                    playerIndent,
                    playerEnd,
                    characterIndent,
                    characterKeys);
        }

        private void acceptFormat(KeyLine line) {
            if (line.valueStart < 0 || !line.valueClosed || line.flow || line.block) {
                throw new Reject();
            }
            sawFormat = true;
            formatValueStart = line.valueStart;
            formatValueEnd = line.valueEnd;
            format2 = formatNumber(scalarText(line)) >= 2;
        }

        private void parseEntries() {
            String player = null;
            int playersAt = -1;
            while (true) {
                KeyLine line = nextKey();
                if (line == null) {
                    closePlayer(player, n);
                    entriesEnd = n;
                    return;
                }
                if (line.raw.indent == 0) {
                    closePlayer(player, line.raw.lineStart);
                    entriesEnd = line.raw.lineStart;
                    hold(line.raw);
                    return;
                }
                if (playersAt < 0) {
                    playersAt = line.raw.indent;
                    playerIndent = line.raw.indent;
                }
                if (line.raw.indent != playersAt || line.valueStart >= 0 || line.flow) {
                    throw new Reject();
                }
                closePlayer(player, line.raw.lineStart);
                player = line.key;
                playerEnd.put(player, -1);
                parsePlayer(player, line.raw.indent);
                player = null;
            }
        }

        private void parsePlayer(String player, int keyIndent) {
            int charactersAt = -1;
            while (true) {
                KeyLine line = nextKey();
                if (line == null) {
                    closePlayer(player, n);
                    return;
                }
                if (line.raw.indent <= keyIndent) {
                    closePlayer(player, line.raw.lineStart);
                    hold(line.raw);
                    return;
                }
                if (charactersAt < 0) {
                    charactersAt = line.raw.indent;
                    characterIndent.put(player, charactersAt);
                }
                if (line.raw.indent != charactersAt) {
                    throw new Reject();
                }
                if (line.flow && !line.flowClosed) {
                    throw new Reject();
                }
                characterKeys.add(player + "\u0000" + line.key);
                if (line.flow && line.flowClosed) {
                    continue;
                }
                skipCharacterBody(line, charactersAt);
            }
        }

        private void closePlayer(String player, int at) {
            if (player == null) {
                return;
            }
            Integer previous = playerEnd.get(player);
            if (previous == null || previous < 0) {
                playerEnd.put(player, at);
            }
        }

        /**
         * The next sibling key stays unread. A quote that is still open on that key is rejected,
         * because the quoted text can contain a line that only looks like a character.
         */
        private void skipCharacterBody(KeyLine keyLine, int keyIndent) {
            boolean single = false;
            boolean doubled = false;
            boolean escape = false;
            if (keyLine.valueStart >= 0 && !keyLine.flow) {
                Quote quote = scanQuotes(keyLine.valueStart, keyLine.raw.lineEnd, false, false, false);
                single = quote.single;
                doubled = quote.doubled;
                escape = quote.escape;
            }
            while (true) {
                int save = index;
                Raw savedHold = held;
                Raw line = peek();
                if (line == null) {
                    return;
                }
                if (!single && !doubled && (line.blank || line.comment)) {
                    consume();
                    continue;
                }
                if (line.indent <= keyIndent) {
                    index = save;
                    held = savedHold;
                    if (single || doubled) {
                        throw new Reject();
                    }
                    return;
                }
                consume();
                Quote quote = scanQuotes(line.content, line.lineEnd, single, doubled, escape);
                single = quote.single;
                doubled = quote.doubled;
                escape = quote.escape;
            }
        }

        private void skipIndented(int parentIndent) {
            while (true) {
                int save = index;
                Raw savedHold = held;
                Raw line = peek();
                if (line == null) {
                    return;
                }
                if (line.blank || line.comment) {
                    consume();
                    continue;
                }
                if (line.indent <= parentIndent) {
                    index = save;
                    held = savedHold;
                    return;
                }
                consume();
            }
        }

        private void skipLeading() {
            while (true) {
                Raw line = peek();
                if (line == null) {
                    return;
                }
                if (line.blank || line.comment) {
                    consume();
                    continue;
                }
                if (line.indent == 0 && "---".equals(slice(line))) {
                    consume();
                }
                return;
            }
        }

        private String scalarText(KeyLine line) {
            String raw = text.substring(line.valueStart, line.valueEnd);
            if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
                return unescapeDouble(raw.substring(1, raw.length() - 1));
            }
            if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
                return raw.substring(1, raw.length() - 1).replace("''", "'");
            }
            return raw;
        }

        private KeyLine nextKey() {
            while (true) {
                Raw line = nextRaw();
                if (line == null) {
                    return null;
                }
                if (line.blank || line.comment) {
                    continue;
                }
                if (line.indent == 0 && "...".equals(slice(line))) {
                    throw new Reject();
                }
                if (line.indent == 0 && "---".equals(slice(line))) {
                    throw new Reject();
                }
                return parseKey(line);
            }
        }

        private void hold(Raw line) {
            held = line;
        }

        private Raw nextRaw() {
            if (held != null) {
                Raw line = held;
                held = null;
                return line;
            }
            return readRaw();
        }

        private Raw peek() {
            if (held != null) {
                return held;
            }
            if (index >= n) {
                return null;
            }
            held = readRaw();
            return held;
        }

        private void consume() {
            if (held != null) {
                held = null;
                return;
            }
            readRaw();
        }

        private Raw readRaw() {
            if (index >= n) {
                return null;
            }
            int lineStart = index;
            int i = index;
            int indent = 0;
            while (i < n) {
                char c = text.charAt(i);
                if (c == ' ') {
                    indent++;
                    i++;
                    continue;
                }
                if (c == '\t') {
                    throw new Reject();
                }
                break;
            }
            int content = i;
            while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                i++;
            }
            int lineEnd = i;
            if (i < n && text.charAt(i) == '\r') {
                i++;
            }
            if (i < n && text.charAt(i) == '\n') {
                i++;
            }
            index = i;
            boolean blank = content >= lineEnd;
            boolean comment = !blank && text.charAt(content) == '#';
            return new Raw(lineStart, indent, content, lineEnd, blank, comment);
        }

        private String slice(Raw line) {
            return text.substring(line.content, line.lineEnd);
        }

        private KeyLine parseKey(Raw line) {
            int cursor = line.content;
            int lineEnd = line.lineEnd;
            if (cursor >= lineEnd) {
                throw new Reject();
            }
            char first = text.charAt(cursor);
            if (first == '-' || first == '?' || first == '{' || first == '[' || first == '&'
                    || first == '*' || first == '!' || first == '|') {
                throw new Reject();
            }
            int keyStart;
            int keyEnd;
            if (first == '"' || first == '\'') {
                int end = closeQuote(cursor, lineEnd, first);
                if (end < 0) {
                    throw new Reject();
                }
                keyStart = cursor + 1;
                keyEnd = end;
                cursor = end + 1;
            } else {
                int colon = plainKeyEnd(cursor, lineEnd);
                if (colon < 0) {
                    throw new Reject();
                }
                keyStart = cursor;
                keyEnd = colon;
                cursor = colon;
            }
            if (cursor >= lineEnd || text.charAt(cursor) != ':') {
                throw new Reject();
            }
            cursor++;
            String key = text.substring(keyStart, keyEnd);
            if (first == '\'') {
                key = key.replace("''", "'");
            } else if (first == '"') {
                key = unescapeDouble(key);
            }
            if (key.isEmpty()) {
                throw new Reject();
            }
            while (cursor < lineEnd && text.charAt(cursor) == ' ') {
                cursor++;
            }
            if (cursor >= lineEnd || text.charAt(cursor) == '#') {
                return new KeyLine(line, key, -1, -1, false, false, true, false);
            }
            char valueFirst = text.charAt(cursor);
            if (valueFirst == '"' || valueFirst == '\'') {
                int end = closeQuote(cursor, lineEnd, valueFirst);
                if (end < 0) {
                    return new KeyLine(line, key, cursor, lineEnd, false, false, false, false);
                }
                if (!onlyComment(end + 1, lineEnd)) {
                    throw new Reject();
                }
                return new KeyLine(line, key, cursor, end + 1, false, false, true, false);
            }
            if (valueFirst == '{' || valueFirst == '[') {
                int end = closeFlow(cursor, lineEnd);
                if (end < 0) {
                    return new KeyLine(line, key, cursor, lineEnd, true, false, false, false);
                }
                if (!onlyComment(end, lineEnd)) {
                    throw new Reject();
                }
                return new KeyLine(line, key, cursor, end, true, true, true, false);
            }
            if (valueFirst == '|' || valueFirst == '>') {
                if (!blockHeader(cursor, lineEnd)) {
                    throw new Reject();
                }
                return new KeyLine(line, key, cursor, lineEnd, false, false, true, true);
            }
            if (valueFirst == '&' || valueFirst == '*' || valueFirst == '!') {
                throw new Reject();
            }
            int valueEnd = lineEnd;
            for (int i = cursor + 1; i < lineEnd; i++) {
                if (text.charAt(i) == '#' && text.charAt(i - 1) == ' ') {
                    valueEnd = i;
                    break;
                }
            }
            while (valueEnd > cursor && text.charAt(valueEnd - 1) == ' ') {
                valueEnd--;
            }
            if (valueEnd <= cursor) {
                throw new Reject();
            }
            return new KeyLine(line, key, cursor, valueEnd, false, false, true, false);
        }

        private boolean blockHeader(int start, int lineEnd) {
            int i = start + 1;
            while (i < lineEnd) {
                char c = text.charAt(i);
                if (c != '+' && c != '-' && !Character.isDigit(c)) {
                    break;
                }
                i++;
            }
            return onlyComment(i, lineEnd);
        }

        private boolean onlyComment(int start, int lineEnd) {
            int i = start;
            while (i < lineEnd && text.charAt(i) == ' ') {
                i++;
            }
            return i >= lineEnd || text.charAt(i) == '#';
        }

        private int plainKeyEnd(int start, int lineEnd) {
            for (int i = start; i < lineEnd; i++) {
                if (text.charAt(i) == ':') {
                    return i;
                }
            }
            return -1;
        }

        private int closeQuote(int start, int lineEnd, char quote) {
            if (quote == '\'') {
                for (int i = start + 1; i < lineEnd; i++) {
                    if (text.charAt(i) != '\'') {
                        continue;
                    }
                    if (i + 1 < lineEnd && text.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    return i;
                }
                return -1;
            }
            boolean escape = false;
            for (int i = start + 1; i < lineEnd; i++) {
                char c = text.charAt(i);
                if (escape) {
                    escape = false;
                    continue;
                }
                if (c == '\\') {
                    escape = true;
                    continue;
                }
                if (c == '"') {
                    return i;
                }
            }
            return -1;
        }

        private int closeFlow(int start, int lineEnd) {
            int depth = 0;
            boolean single = false;
            boolean doubled = false;
            boolean escape = false;
            for (int i = start; i < lineEnd; i++) {
                char c = text.charAt(i);
                if (doubled) {
                    if (escape) {
                        escape = false;
                        continue;
                    }
                    if (c == '\\') {
                        escape = true;
                        continue;
                    }
                    if (c == '"') {
                        doubled = false;
                    }
                    continue;
                }
                if (single) {
                    if (c == '\'') {
                        if (i + 1 < lineEnd && text.charAt(i + 1) == '\'') {
                            i++;
                            continue;
                        }
                        single = false;
                    }
                    continue;
                }
                if (c == '"') {
                    doubled = true;
                    continue;
                }
                if (c == '\'') {
                    single = true;
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                    continue;
                }
                if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                    if (depth < 0) {
                        return -1;
                    }
                }
            }
            return -1;
        }

        private Quote scanQuotes(int start, int end, boolean single, boolean doubled, boolean escape) {
            for (int i = Math.max(0, start); i < end; i++) {
                char c = text.charAt(i);
                if (doubled) {
                    if (escape) {
                        escape = false;
                        continue;
                    }
                    if (c == '\\') {
                        escape = true;
                        continue;
                    }
                    if (c == '"') {
                        doubled = false;
                    }
                    continue;
                }
                if (single) {
                    if (c == '\'') {
                        if (i + 1 < end && text.charAt(i + 1) == '\'') {
                            i++;
                            continue;
                        }
                        single = false;
                    }
                    continue;
                }
                if (c == '#' && (i == start || text.charAt(i - 1) == ' ')) {
                    break;
                }
                if (c == '"') {
                    doubled = true;
                    continue;
                }
                if (c == '\'') {
                    single = true;
                }
            }
            return new Quote(single, doubled, escape && doubled);
        }
    }

    private static String unescapeDouble(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            out.append(switch (next) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case '"' -> '"';
                case '\\' -> '\\';
                default -> next;
            });
        }
        return out.toString();
    }

    private static int formatNumber(String rest) {
        try {
            return Integer.parseInt(rest == null ? "" : rest.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Quote(boolean single, boolean doubled, boolean escape) {
    }

    private static final class Raw {
        private final int lineStart;
        private final int indent;
        private final int content;
        private final int lineEnd;
        private final boolean blank;
        private final boolean comment;

        private Raw(int lineStart, int indent, int content, int lineEnd, boolean blank, boolean comment) {
            this.lineStart = lineStart;
            this.indent = indent;
            this.content = content;
            this.lineEnd = lineEnd;
            this.blank = blank;
            this.comment = comment;
        }
    }

    private static final class KeyLine {
        private final Raw raw;
        private final String key;
        private final int valueStart;
        private final int valueEnd;
        private final boolean flow;
        private final boolean flowClosed;
        private final boolean valueClosed;
        private final boolean block;

        private KeyLine(
                Raw raw,
                String key,
                int valueStart,
                int valueEnd,
                boolean flow,
                boolean flowClosed,
                boolean valueClosed,
                boolean block
        ) {
            this.raw = raw;
            this.key = key;
            this.valueStart = valueStart;
            this.valueEnd = valueEnd;
            this.flow = flow;
            this.flowClosed = flowClosed;
            this.valueClosed = valueClosed;
            this.block = block;
        }
    }
}
