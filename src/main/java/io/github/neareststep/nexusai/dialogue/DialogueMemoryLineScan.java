package io.github.neareststep.nexusai.dialogue;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Line-oriented outline of {@code dialogue-memory.yml} for stop splice. Handles the block shape
 * MemoryStore writes and the large hand-built fixtures used in stop timing tests. Flow nodes,
 * anchors, tags, and complex keys are refused so the caller can fall back to the event scan.
 */
final class DialogueMemoryLineScan {

    private static final Resolver PLAIN = new Resolver();
    private static final Yaml PLAIN_SCALAR = new Yaml();

    private DialogueMemoryLineScan() {
    }

    static final class Result {
        final boolean refused;
        final boolean unreadable;
        final Outline outline;

        private Result(boolean refused, boolean unreadable, Outline outline) {
            this.refused = refused;
            this.unreadable = unreadable;
            this.outline = outline;
        }

        static Result refused() {
            return new Result(true, false, null);
        }

        static Result unreadable() {
            return new Result(true, true, null);
        }

        static Result ok(Outline outline) {
            return new Result(false, false, outline);
        }
    }

    static final class Outline {
        boolean format2;
        boolean sawFormat;
        int formatValueStart = -1;
        int formatValueEnd = -1;
        boolean sawEntries;
        boolean otherRoot;
        int entriesEnd = -1;
        int playerIndent = 2;
        final Map<String, Integer> playerEnd = new LinkedHashMap<>();
        final Map<String, Integer> characterIndent = new LinkedHashMap<>();
        final Set<String> characterKeys = new java.util.HashSet<>();
        final Map<String, int[]> nullSpans = new LinkedHashMap<>();
    }

    static Result scan(String text) {
        return scan(text, null);
    }

    static Result scan(String text, BooleanSupplier abandoned) throws InterruptedScan {
        if (text == null) {
            return Result.refused();
        }
        try {
            return scanBody(text, abandoned);
        } catch (InterruptedScan e) {
            throw e;
        } catch (ScanRefuse e) {
            return Result.refused();
        } catch (RuntimeException e) {
            return Result.unreadable();
        }
    }

    /** Thrown when stop abandons an in-flight line scan. */
    static final class InterruptedScan extends RuntimeException {
        private InterruptedScan() {
            super(null, null, false, false);
        }
    }

    private static void checkAbandoned(BooleanSupplier abandoned) {
        if (abandoned != null && abandoned.getAsBoolean()) {
            throw new InterruptedScan();
        }
    }

    private static Result scanBody(String text, BooleanSupplier abandoned) {
        Outline outline = new Outline();
        int n = text.length();
        int i = 0;
        int codePoint = 0;
        String currentPlayer = null;
        int playerKeyColumn = -1;
        int playerChildColumn = -1;
        int entriesContentColumn = -1;
        boolean inEntries = false;
        int blockMinIndent = -1;
        boolean inDouble = false;
        boolean inSingle = false;
        int sequenceItemIndent = -1;
        int sequenceValueIndent = -1;

        while (i < n) {
            checkAbandoned(abandoned);
            int lineStartCp = codePoint;
            int indent = 0;
            while (i < n) {
                char c = text.charAt(i);
                if (c == ' ') {
                    indent++;
                    i++;
                    codePoint++;
                    continue;
                }
                if (c == '\t') {
                    throw new ScanRefuse();
                }
                break;
            }
            if (i >= n) {
                break;
            }
            char first = text.charAt(i);
            if (first == '\n' || first == '\r') {
                int next = skipNewline(text, i);
                codePoint += countCodePoints(text, i, next);
                i = next;
                continue;
            }

            if (blockMinIndent >= 0) {
                if (indent > blockMinIndent) {
                    int next = skipLineContent(text, i, n);
                    codePoint += countCodePoints(text, i, next);
                    i = next;
                    continue;
                }
                blockMinIndent = -1;
            }

            if (inDouble || inSingle) {
                boolean still = continueQuoted(text, i, n, inDouble);
                int next = skipLineContent(text, i, n);
                codePoint += countCodePoints(text, i, next);
                i = next;
                if (still) {
                    continue;
                }
                inDouble = false;
                inSingle = false;
                continue;
            }

            if (first == '#') {
                int next = skipLineContent(text, i, n);
                codePoint += countCodePoints(text, i, next);
                i = next;
                continue;
            }

            if (first == '?' && (i + 1 >= n || isSpaceOrBreak(text.charAt(i + 1)))) {
                throw new ScanRefuse();
            }

            if (first == '-' && (i + 1 >= n || text.charAt(i + 1) == ' ' || isBreak(text.charAt(i + 1)))) {
                int afterDash = i + 1;
                while (afterDash < n && text.charAt(afterDash) == ' ') {
                    afterDash++;
                }
                if (afterDash < n && !isBreak(text.charAt(afterDash)) && text.charAt(afterDash) != '#') {
                    char look = text.charAt(afterDash);
                    if (look == '{' || look == '[') {
                        throw new ScanRefuse();
                    }
                    if (look == '&' || look == '*' || look == '!') {
                        throw new ScanRefuse();
                    }
                }
                sequenceItemIndent = indent;
                sequenceValueIndent = indent + 2;
                QuoteState quote = scanLineForQuotesAndFlow(text, afterDash, n, indent);
                if (quote.startBlock >= 0) {
                    blockMinIndent = indent;
                }
                inDouble = quote.continueDouble;
                inSingle = quote.continueSingle;
                int next = skipLineContent(text, i, n);
                codePoint += countCodePoints(text, i, next);
                i = next;
                continue;
            }

            if (sequenceItemIndent >= 0 && indent <= sequenceItemIndent) {
                sequenceItemIndent = -1;
                sequenceValueIndent = -1;
            }
            if (sequenceItemIndent >= 0 && indent > sequenceValueIndent) {
                // Over-indented under a list item (Bukkit/SnakeYAML rejects this shape).
                throw new ScanRefuse();
            }

            KeyLine keyLine = readKeyLine(text, i, n, lineStartCp, indent);
            if (keyLine == null) {
                throw new ScanRefuse();
            }
            keyLine = resolveEmptyMapping(text, keyLine);

            if (inEntries && currentPlayer != null && indent <= playerKeyColumn) {
                outline.playerEnd.put(currentPlayer, lineStartCp);
                outline.characterIndent.put(currentPlayer,
                        playerChildColumn < 0 ? playerKeyColumn + 2 : playerChildColumn);
                currentPlayer = null;
                playerChildColumn = -1;
                sequenceItemIndent = -1;
                sequenceValueIndent = -1;
            }

            if (indent == 0) {
                if (inEntries && currentPlayer != null) {
                    outline.playerEnd.put(currentPlayer, lineStartCp);
                    outline.characterIndent.put(currentPlayer,
                            playerChildColumn < 0 ? playerKeyColumn + 2 : playerChildColumn);
                    currentPlayer = null;
                    playerChildColumn = -1;
                }
                if (inEntries && outline.entriesEnd < 0 && !"entries".equals(keyLine.decodedKey)) {
                    outline.entriesEnd = lineStartCp;
                    inEntries = false;
                }
                if ("entries".equals(keyLine.decodedKey)) {
                    if (!keyLine.valueIsNull && !keyLine.nestedMapping) {
                        throw new ScanRefuse();
                    }
                    outline.sawEntries = true;
                    inEntries = true;
                    entriesContentColumn = -1;
                    if (keyLine.startsBlock) {
                        throw new ScanRefuse();
                    }
                } else if ("format".equals(keyLine.decodedKey)) {
                    outline.sawFormat = true;
                    outline.format2 = formatNumber(keyLine.valueText) >= 2;
                    outline.formatValueStart = keyLine.valueStartCp;
                    outline.formatValueEnd = keyLine.valueEndCp;
                } else {
                    outline.otherRoot = true;
                }
                QuoteState quote = applyValueState(keyLine);
                if (quote.startBlock >= 0) {
                    blockMinIndent = indent;
                }
                inDouble = quote.continueDouble;
                inSingle = quote.continueSingle;
                codePoint += countCodePoints(text, i, keyLine.lineEnd);
                i = keyLine.lineEnd;
                continue;
            }

            if (!inEntries) {
                throw new ScanRefuse();
            }

            if (entriesContentColumn < 0) {
                entriesContentColumn = indent;
                outline.playerIndent = indent;
            }

            if (indent == entriesContentColumn) {
                if (currentPlayer != null) {
                    outline.playerEnd.put(currentPlayer, lineStartCp);
                    outline.characterIndent.put(currentPlayer,
                            playerChildColumn < 0 ? playerKeyColumn + 2 : playerChildColumn);
                }
                currentPlayer = keyLine.decodedKey;
                playerKeyColumn = indent;
                playerChildColumn = -1;
                sequenceItemIndent = -1;
                sequenceValueIndent = -1;
                outline.playerEnd.put(currentPlayer, -1);
                if (keyLine.hasFlow) {
                    throw new ScanRefuse();
                }
                if (!keyLine.valueIsNull && !keyLine.startsBlock && !keyLine.nestedMapping) {
                    throw new ScanRefuse();
                }
                QuoteState quote = applyValueState(keyLine);
                if (quote.startBlock >= 0) {
                    blockMinIndent = indent;
                }
                inDouble = quote.continueDouble;
                inSingle = quote.continueSingle;
                codePoint += countCodePoints(text, i, keyLine.lineEnd);
                i = keyLine.lineEnd;
                continue;
            }

            if (currentPlayer == null) {
                throw new ScanRefuse();
            }

            if (playerChildColumn < 0) {
                playerChildColumn = indent;
            }

            if (indent == playerChildColumn) {
                sequenceItemIndent = -1;
                sequenceValueIndent = -1;
                String characterId = keyLine.decodedKey;
                String full = currentPlayer + "\u0000" + characterId;
                if (keyLine.valueIsNull && !keyLine.startsBlock && !keyLine.hasFlow && !keyLine.nestedMapping) {
                    outline.nullSpans.put(full, new int[] {keyLine.keyStartCp, keyLine.valueEndCp});
                } else {
                    outline.characterKeys.add(full);
                }
                if (keyLine.hasFlow) {
                    throw new ScanRefuse();
                }
                QuoteState quote = applyValueState(keyLine);
                if (quote.startBlock >= 0) {
                    blockMinIndent = indent;
                }
                inDouble = quote.continueDouble;
                inSingle = quote.continueSingle;
                codePoint += countCodePoints(text, i, keyLine.lineEnd);
                i = keyLine.lineEnd;
                continue;
            }

            if (indent > playerChildColumn) {
                if (keyLine.hasFlow) {
                    throw new ScanRefuse();
                }
                QuoteState quote = applyValueState(keyLine);
                if (quote.startBlock >= 0) {
                    blockMinIndent = indent;
                }
                inDouble = quote.continueDouble;
                inSingle = quote.continueSingle;
                codePoint += countCodePoints(text, i, keyLine.lineEnd);
                i = keyLine.lineEnd;
                continue;
            }

            throw new ScanRefuse();
        }

        if (inDouble || inSingle) {
            throw new ScanRefuse();
        }

        int endCp = codePoint;
        if (currentPlayer != null) {
            outline.playerEnd.put(currentPlayer, endCp);
            outline.characterIndent.put(currentPlayer,
                    playerChildColumn < 0 ? playerKeyColumn + 2 : playerChildColumn);
        }
        if (inEntries) {
            outline.entriesEnd = endCp;
        }
        return Result.ok(outline);
    }

    private static QuoteState applyValueState(KeyLine keyLine) {
        QuoteState state = new QuoteState();
        if (keyLine.startsBlock) {
            state.startBlock = 0;
        }
        state.continueDouble = keyLine.continueDouble;
        state.continueSingle = keyLine.continueSingle;
        return state;
    }

    /**
     * A key line with nothing after {@code :} is a nested mapping when the next content line is
     * indented further, and a null/empty scalar when it is not.
     */
    private static KeyLine resolveEmptyMapping(String text, KeyLine keyLine) {
        if (!keyLine.valueIsNull || keyLine.startsBlock || keyLine.hasFlow || keyLine.nestedMapping) {
            return keyLine;
        }
        int next = nextContentIndent(text, keyLine.lineEnd);
        if (next > keyLine.indent) {
            return keyLine.withNestedMapping();
        }
        return keyLine;
    }

    private static int nextContentIndent(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            int indent = 0;
            while (i < n && text.charAt(i) == ' ') {
                indent++;
                i++;
            }
            if (i >= n) {
                return -1;
            }
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                i = skipNewline(text, i);
                continue;
            }
            if (c == '#') {
                i = skipLineContent(text, i, n);
                continue;
            }
            return indent;
        }
        return -1;
    }

    private static KeyLine readKeyLine(String text, int content, int n, int lineStartCp, int indent) {
        char first = text.charAt(content);
        if (first == '{' || first == '[' || first == '&' || first == '*' || first == '!') {
            throw new ScanRefuse();
        }
        int keyStartCp = lineStartCp + indent;
        String raw;
        String decoded;
        int keyEnd;
        if (first == '\'' || first == '"') {
            ParsedScalar quoted = readQuotedOnLine(text, content, n, first);
            if (!quoted.closed) {
                throw new ScanRefuse();
            }
            raw = quoted.raw;
            decoded = quoted.decoded;
            keyEnd = quoted.end;
            while (keyEnd < n && text.charAt(keyEnd) == ' ') {
                keyEnd++;
            }
            if (keyEnd >= n || text.charAt(keyEnd) != ':') {
                return null;
            }
        } else {
            int colon = findMappingColon(text, content, n);
            if (colon < 0) {
                return null;
            }
            raw = text.substring(content, colon).stripTrailing();
            if (raw.isEmpty() || content + raw.length() != colon && text.substring(content, colon).chars().anyMatch(ch -> ch != ' ' && ch != '\t')) {
                // Trailing spaces before the mapping colon are unusual in files we write; refuse.
                if (!raw.equals(text.substring(content, colon).stripTrailing())
                        || text.substring(content + raw.length(), colon).chars().anyMatch(ch -> ch != ' ')) {
                    throw new ScanRefuse();
                }
            }
            if (raw.isEmpty()) {
                return null;
            }
            decoded = decodePlainKey(raw);
            keyEnd = colon;
        }
        int valueStart = keyEnd + 1;
        while (valueStart < n && text.charAt(valueStart) == ' ') {
            valueStart++;
        }
        int lineEnd = skipLineContent(text, content, n);
        // lineStartCp is the code-point index of this line's first column; content is UTF-16 at
        // indent spaces (ASCII), so offsets from content stay code-point offsets for BMP keys.
        int contentCp = lineStartCp + indent;
        if (valueStart >= n || isBreak(text.charAt(valueStart)) || text.charAt(valueStart) == '#') {
            int valueEndCp = contentCp + countCodePoints(text, content, Math.min(valueStart, lineEnd));
            return new KeyLine(raw, decoded, true, false, false, false, false,
                    keyStartCp, valueEndCp, valueEndCp, lineEnd, indent, null);
        }
        char valueFirst = text.charAt(valueStart);
        if (valueFirst == '{' || valueFirst == '[') {
            return new KeyLine(raw, decoded, false, true, false, false, false,
                    keyStartCp, -1, -1, lineEnd, indent, null);
        }
        if (valueFirst == '&' || valueFirst == '*' || valueFirst == '!') {
            throw new ScanRefuse();
        }
        if (valueFirst == '|' || valueFirst == '>') {
            return new KeyLine(raw, decoded, false, false, true, false, false,
                    keyStartCp, -1, -1, lineEnd, indent, null);
        }
        boolean continueDouble = false;
        boolean continueSingle = false;
        String valueText;
        int valueStartCp = contentCp + countCodePoints(text, content, valueStart);
        int valueEndCp;
        if (valueFirst == '\'' || valueFirst == '"') {
            ParsedScalar quoted = readQuotedOnLine(text, valueStart, n, valueFirst);
            valueText = quoted.decoded;
            valueEndCp = contentCp + countCodePoints(text, content, quoted.end);
            if (!quoted.closed) {
                if (valueFirst == '"') {
                    continueDouble = true;
                } else {
                    continueSingle = true;
                }
            }
            int trail = quoted.end;
            while (trail < n && text.charAt(trail) == ' ') {
                trail++;
            }
            if (trail < n && text.charAt(trail) != '#' && !isBreak(text.charAt(trail)) && quoted.closed) {
                throw new ScanRefuse();
            }
        } else {
            int valueEnd = valueStart;
            while (valueEnd < n && !isBreak(text.charAt(valueEnd)) && text.charAt(valueEnd) != '#') {
                char c = text.charAt(valueEnd);
                if (c == '{' || c == '[') {
                    throw new ScanRefuse();
                }
                valueEnd++;
            }
            valueText = text.substring(valueStart, valueEnd).stripTrailing();
            valueEndCp = contentCp + countCodePoints(text, content, valueStart + valueText.length());
        }
        boolean isNull = isPlainNull(valueText);
        return new KeyLine(raw, decoded, isNull, false, false, continueDouble, continueSingle,
                keyStartCp, valueStartCp, valueEndCp, lineEnd, indent, valueText);
    }

    /**
     * Index of the mapping {@code :} that ends a plain key. Colons inside the key (as in
     * {@code ns:npc}) are not followed by space and are skipped.
     */
    private static int findMappingColon(String text, int content, int n) {
        int at = content;
        while (at < n) {
            char c = text.charAt(at);
            if (isBreak(c) || c == '#') {
                return -1;
            }
            if (c == '{' || c == '[' || c == ']' || c == '}' || c == ',' || c == '&' || c == '*' || c == '!') {
                throw new ScanRefuse();
            }
            if (c == ':') {
                char next = at + 1 < n ? text.charAt(at + 1) : '\n';
                if (next == ' ' || isBreak(next) || next == '#') {
                    return at;
                }
            }
            at++;
        }
        return -1;
    }

    /** True when the quote is still open after this physical line. */
    private static boolean continueQuoted(String text, int start, int n, boolean doubleQuote) {
        int at = start;
        if (doubleQuote) {
            while (at < n && !isBreak(text.charAt(at))) {
                char c = text.charAt(at);
                if (c == '\\') {
                    if (at + 1 < n && isBreak(text.charAt(at + 1))) {
                        return true;
                    }
                    at += 2;
                    continue;
                }
                if (c == '"') {
                    return false;
                }
                at++;
            }
            return true;
        }
        while (at < n && !isBreak(text.charAt(at))) {
            char c = text.charAt(at);
            if (c == '\'') {
                if (at + 1 < n && text.charAt(at + 1) == '\'') {
                    at += 2;
                    continue;
                }
                return false;
            }
            at++;
        }
        return true;
    }

    private static ParsedScalar readQuotedOnLine(String text, int start, int n, char quote) {
        StringBuilder decoded = new StringBuilder();
        int at = start + 1;
        boolean closed = false;
        while (at < n) {
            char c = text.charAt(at);
            if (isBreak(c)) {
                break;
            }
            if (quote == '\'' && c == '\'') {
                if (at + 1 < n && text.charAt(at + 1) == '\'') {
                    decoded.append('\'');
                    at += 2;
                    continue;
                }
                closed = true;
                at++;
                break;
            }
            if (quote == '"' && c == '\\') {
                if (at + 1 < n && isBreak(text.charAt(at + 1))) {
                    // line continuation
                    at++;
                    break;
                }
                // Escape sequences need the event scan; do not guess YAML escapes here.
                throw new ScanRefuse();
            }
            if (quote == '"' && c == '"') {
                closed = true;
                at++;
                break;
            }
            decoded.append(c);
            at++;
        }
        return new ParsedScalar(text.substring(start, at), decoded.toString(), at, closed);
    }

    private static QuoteState scanLineForQuotesAndFlow(String text, int start, int n, int indent) {
        QuoteState state = new QuoteState();
        boolean inDouble = false;
        boolean inSingle = false;
        for (int at = start; at < n; at++) {
            char c = text.charAt(at);
            if (isBreak(c)) {
                break;
            }
            if (!inDouble && !inSingle) {
                if (c == '#') {
                    break;
                }
                if (c == '{' || c == '[') {
                    throw new ScanRefuse();
                }
                if (c == '|' || c == '>') {
                    state.startBlock = indent;
                }
                if (c == '\'') {
                    inSingle = true;
                    continue;
                }
                if (c == '"') {
                    inDouble = true;
                    continue;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'') {
                    if (at + 1 < n && text.charAt(at + 1) == '\'') {
                        at++;
                        continue;
                    }
                    inSingle = false;
                }
                continue;
            }
            if (c == '\\' && at + 1 < n && isBreak(text.charAt(at + 1))) {
                state.continueDouble = true;
                inDouble = false;
                break;
            }
            if (c == '\\') {
                at++;
                continue;
            }
            if (c == '"') {
                inDouble = false;
            }
        }
        state.continueSingle = inSingle;
        state.continueDouble = state.continueDouble || inDouble;
        return state;
    }

    private static int skipQuotedContinuation(String text, int start, int n, boolean doubleQuote) {
        int at = start;
        if (doubleQuote) {
            while (at < n) {
                char c = text.charAt(at);
                if (c == '\\' && at + 1 < n && isBreak(text.charAt(at + 1))) {
                    return skipLineContent(text, start, n);
                }
                if (c == '\\') {
                    at += 2;
                    continue;
                }
                if (c == '"') {
                    return skipLineContent(text, start, n);
                }
                if (isBreak(c)) {
                    return skipLineContent(text, start, n);
                }
                at++;
            }
            return n;
        }
        while (at < n) {
            char c = text.charAt(at);
            if (c == '\'') {
                if (at + 1 < n && text.charAt(at + 1) == '\'') {
                    at += 2;
                    continue;
                }
                return skipLineContent(text, start, n);
            }
            if (isBreak(c)) {
                return skipLineContent(text, start, n);
            }
            at++;
        }
        return n;
    }

    private static String decodePlainKey(String raw) {
        if ("==".equals(raw)) {
            throw new ScanRefuse();
        }
        Tag tag = PLAIN.resolve(NodeId.scalar, raw, true);
        if (Tag.STR.equals(tag)) {
            return raw;
        }
        if (Tag.NULL.equals(tag)) {
            throw new ScanRefuse();
        }
        Object value;
        synchronized (PLAIN_SCALAR) {
            try {
                value = PLAIN_SCALAR.load(raw);
            } catch (RuntimeException e) {
                throw new ScanRefuse();
            }
        }
        if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
            throw new ScanRefuse();
        }
        return String.valueOf(value);
    }

    private static boolean isPlainNull(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return Tag.NULL.equals(PLAIN.resolve(NodeId.scalar, value, true));
    }

    private static int formatNumber(String rest) {
        try {
            return Integer.parseInt(rest == null ? "" : rest.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int skipLineContent(String text, int i, int n) {
        while (i < n && !isBreak(text.charAt(i))) {
            i++;
        }
        return skipNewline(text, i);
    }

    private static int skipNewline(String text, int i) {
        if (i >= text.length()) {
            return i;
        }
        char c = text.charAt(i);
        if (c == '\r') {
            i++;
            if (i < text.length() && text.charAt(i) == '\n') {
                i++;
            }
            return i;
        }
        if (c == '\n') {
            return i + 1;
        }
        return i;
    }

    private static int countCodePoints(String text, int from, int to) {
        int count = 0;
        int i = from;
        while (i < to) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            count++;
        }
        return count;
    }

    private static boolean isBreak(char c) {
        return c == '\n' || c == '\r';
    }

    private static boolean isSpaceOrBreak(char c) {
        return c == ' ' || c == '\t' || isBreak(c);
    }

    private static final class KeyLine {
        private final String decodedKey;
        private final boolean valueIsNull;
        private final boolean hasFlow;
        private final boolean startsBlock;
        private final boolean nestedMapping;
        private final boolean continueDouble;
        private final boolean continueSingle;
        private final int keyStartCp;
        private final int valueStartCp;
        private final int valueEndCp;
        private final int lineEnd;
        private final int indent;
        private final String valueText;

        private KeyLine(
                String rawKey,
                String decodedKey,
                boolean valueIsNull,
                boolean hasFlow,
                boolean startsBlock,
                boolean continueDouble,
                boolean continueSingle,
                int keyStartCp,
                int valueStartCp,
                int valueEndCp,
                int lineEnd,
                int indent,
                String valueText
        ) {
            this(decodedKey, valueIsNull, hasFlow, startsBlock, false, continueDouble, continueSingle,
                    keyStartCp, valueStartCp, valueEndCp, lineEnd, indent, valueText);
        }

        private KeyLine(
                String decodedKey,
                boolean valueIsNull,
                boolean hasFlow,
                boolean startsBlock,
                boolean nestedMapping,
                boolean continueDouble,
                boolean continueSingle,
                int keyStartCp,
                int valueStartCp,
                int valueEndCp,
                int lineEnd,
                int indent,
                String valueText
        ) {
            this.decodedKey = decodedKey;
            this.valueIsNull = valueIsNull;
            this.hasFlow = hasFlow;
            this.startsBlock = startsBlock;
            this.nestedMapping = nestedMapping;
            this.continueDouble = continueDouble;
            this.continueSingle = continueSingle;
            this.keyStartCp = keyStartCp;
            this.valueStartCp = valueStartCp;
            this.valueEndCp = valueEndCp;
            this.lineEnd = lineEnd;
            this.indent = indent;
            this.valueText = valueText;
        }

        private KeyLine withNestedMapping() {
            return new KeyLine(decodedKey, false, hasFlow, startsBlock, true, continueDouble, continueSingle,
                    keyStartCp, valueStartCp, valueEndCp, lineEnd, indent, valueText);
        }
    }

    private static final class ParsedScalar {
        private final String raw;
        private final String decoded;
        private final int end;
        private final boolean closed;

        private ParsedScalar(String raw, String decoded, int end, boolean closed) {
            this.raw = raw;
            this.decoded = decoded;
            this.end = end;
            this.closed = closed;
        }
    }

    private static final class QuoteState {
        private int startBlock = -1;
        private boolean continueDouble;
        private boolean continueSingle;
    }

    private static final class ScanRefuse extends RuntimeException {
        private ScanRefuse() {
            super(null, null, false, false);
        }
    }
}
