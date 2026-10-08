package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.config.LogRedaction;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import io.github.neareststep.nexusai.api.KnowledgeSelect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Loads {@code plugins/NexusAI/knowledge/*.md} and {@code *.txt}.
 * A prompt names files without the extension. There is no vector search.
 */
public final class KnowledgeBase {

    public static final String OPEN = "----- KNOWLEDGE -----";
    public static final String CLOSE = "----- END KNOWLEDGE -----";
    public static final String EXAMPLE_FILE = "example.md";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String EXAMPLE = """
            <!--
              NexusAI knowledge example.
              This file is created once and is never overwritten.
              A prompt includes it with:
                knowledge:
                  - example
              The name is the file name without .md or .txt.
              In keywords mode, lines that start with # are section headings and <!-- keywords: ... -->
              adds words for that paragraph. Those comments are not sent to the model.
              In full mode the file is sent as written, including comments.
            -->

            # Harbor

            The harbor is old. Ships pay the dock fee before they unload.

            # Rules

            <!-- keywords: grief, steal -->
            Do not grief builds or steal from chests.

            Ask in chat when a rule is unclear.
            """;

    private final Map<String, String> files;
    private final Map<String, String> keywordFiles;
    private final Set<String> invalidUtf8;
    private final Set<String> fullTruncated;
    private final int fullFileChars;
    private final int maxChars;
    private final KeywordSelector selector;
    private final Logger logger;
    private final Set<String> truncationWarned = ConcurrentHashMap.newKeySet();
    private final Set<String> fullTruncationWarned = ConcurrentHashMap.newKeySet();

    private KnowledgeBase(
            Map<String, String> files,
            Map<String, String> keywordFiles,
            Set<String> invalidUtf8,
            Set<String> fullTruncated,
            int fullFileChars,
            int maxChars,
            KeywordSettings keywords,
            Logger logger
    ) {
        this.files = files;
        this.keywordFiles = keywordFiles == null ? Map.of() : keywordFiles;
        this.invalidUtf8 = invalidUtf8 == null ? Set.of() : Set.copyOf(invalidUtf8);
        this.fullTruncated = fullTruncated == null ? Set.of() : Set.copyOf(fullTruncated);
        this.fullFileChars = Math.max(1, fullFileChars);
        this.maxChars = Math.max(1, maxChars);
        this.logger = logger == null ? Logger.getLogger("nexusai.knowledge") : logger;
        Tokenizer tokenizer = Tokenizer.builtin(keywords == null ? List.of() : keywords.stopWords());
        KnowledgeIndex index = KnowledgeIndex.build(this.keywordFiles, tokenizer);
        this.selector = new KeywordSelector(index, tokenizer, keywords, this.maxChars, this.logger);
    }

    public static KnowledgeBase empty() {
        return new KnowledgeBase(Map.of(), Map.of(), Set.of(), Set.of(), 4000, 6000, KeywordSettings.defaults(), null);
    }

    /**
     * Creates {@code example.md} when it is missing. An existing file is left untouched.
     */
    public static void ensureExample(Path folder) throws IOException {
        if (folder == null) {
            return;
        }
        Files.createDirectories(folder);
        Path example = folder.resolve(EXAMPLE_FILE);
        if (Files.exists(example)) {
            return;
        }
        Files.writeString(example, EXAMPLE, StandardCharsets.UTF_8);
    }

    public static KnowledgeBase load(Path folder, int maxChars, int maxFileChars, List<String> warnings, Logger logger) {
        return load(folder, maxChars, maxFileChars, warnings, logger, List.of());
    }

    public static KnowledgeBase load(
            Path folder,
            int maxChars,
            int maxFileChars,
            List<String> warnings,
            Logger logger,
            Iterable<String> secrets
    ) {
        return load(folder, maxChars, maxFileChars, KeywordSettings.legacyFileCap(maxFileChars), true, warnings, logger, secrets);
    }

    /**
     * @param warnFullTruncationAtLoad when false, a file longer than {@code knowledge.max-file-chars}
     *                                  is remembered and warned later, only if a full-mode prompt uses it
     */
    public static KnowledgeBase load(
            Path folder,
            int maxChars,
            int maxFileChars,
            KeywordSettings keywords,
            boolean warnFullTruncationAtLoad,
            List<String> warnings,
            Logger logger,
            Iterable<String> secrets
    ) {
        List<String> notes = warnings == null ? new ArrayList<>() : warnings;
        KeywordSettings keywordSettings = keywords == null ? KeywordSettings.defaults() : keywords;
        int fullCap = Math.max(1, maxFileChars);
        int keywordCap = Math.max(1, keywordSettings.maxFileChars());
        boolean sameCap = fullCap == keywordCap;
        Map<String, String> loaded = new LinkedHashMap<>();
        Map<String, String> indexed = new LinkedHashMap<>();
        Set<String> invalidUtf8 = new LinkedHashSet<>();
        Set<String> fullTruncated = new LinkedHashSet<>();
        if (folder != null && Files.isDirectory(folder)) {
            try (var stream = Files.list(folder)) {
                List<Path> paths = stream
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
                for (Path path : paths) {
                    String fileName = path.getFileName().toString();
                    String lower = fileName.toLowerCase(Locale.ROOT);
                    String ext;
                    if (lower.endsWith(".md")) {
                        ext = ".md";
                    } else if (lower.endsWith(".txt")) {
                        ext = ".txt";
                    } else {
                        continue;
                    }
                    String name = fileName.substring(0, fileName.length() - ext.length());
                    if (!NAME.matcher(name).matches()) {
                        notes.add("Skipping knowledge file '" + fileName + "': the name must match [A-Za-z0-9_-].");
                        continue;
                    }
                    if (loaded.containsKey(name)) {
                        notes.add("Knowledge file '" + fileName + "' was skipped because '" + name + "' is already loaded.");
                        continue;
                    }
                    String text;
                    try {
                        text = Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        if (isNotUtf8(e)) {
                            invalidUtf8.add(name);
                            String note = "Knowledge file '" + fileName
                                    + "' is not valid UTF-8, re-save the file as UTF-8. The file was skipped.";
                            if (logger != null) {
                                logger.warning(note);
                                logger.log(Level.FINE, note, LogRedaction.redactThrowable(e, secrets));
                            } else {
                                notes.add(note);
                            }
                        } else {
                            notes.add("Could not read knowledge file '" + fileName + "'.");
                            if (logger != null) {
                                LogRedaction.warning(logger, "Could not read knowledge file " + fileName, e, secrets);
                            }
                        }
                        continue;
                    }
                    String fullText = cap(text, fullCap, sameCap && warnFullTruncationAtLoad, name, notes);
                    String keywordText = sameCap ? fullText : cap(text, keywordCap, true, name, notes);
                    if (!sameCap && text.length() > fullCap) {
                        if (warnFullTruncationAtLoad) {
                            notes.add("Knowledge file '" + name + "' was truncated to " + fullCap + " characters.");
                        } else {
                            fullTruncated.add(name);
                        }
                    }
                    if (fullText.isBlank() && keywordText.isBlank()) {
                        continue;
                    }
                    loaded.put(name, fullText);
                    indexed.put(name, keywordText.isBlank() ? fullText : keywordText);
                }
            } catch (IOException e) {
                notes.add("Could not list the knowledge folder.");
                if (logger != null) {
                    LogRedaction.warning(logger, "Could not list the knowledge folder", e, secrets);
                }
            }
        }
        return new KnowledgeBase(
                Map.copyOf(loaded),
                Map.copyOf(indexed),
                invalidUtf8,
                fullTruncated,
                fullCap,
                maxChars,
                keywordSettings,
                logger);
    }

    /**
     * One warning per file, and only for files a prompt actually uses while selection is {@code full}.
     */
    public void warnFullModeTruncation(Collection<String> names, Logger target) {
        if (names == null || names.isEmpty() || fullTruncated.isEmpty()) {
            return;
        }
        Logger out = target == null ? logger : target;
        for (String name : names) {
            if (name == null || !fullTruncated.contains(name) || !fullTruncationWarned.add(name)) {
                continue;
            }
            out.warning("Knowledge file '" + name + "' was truncated to " + fullFileChars + " characters.");
        }
    }

    /**
     * Block, cache token, and the {@code /nai test} summary for this request.
     * {@link KnowledgeSelect#FULL} is the historical whole-file block.
     */
    public Piece render(List<String> names, KnowledgeRequest request) {
        KnowledgeRequest effective = request == null ? KnowledgeRequest.full() : request;
        if (effective.mode() != KnowledgeSelect.KEYWORDS) {
            String block = block(names);
            return new Piece(block, sha256(block), KnowledgeSummaries.format(included(names), KnowledgeSelect.FULL));
        }
        KeywordSelector.Selection selection = selector.select(names, effective.text(), effective.keywords());
        String block = wrap(selection.content());
        return new Piece(block, sha256(block), KnowledgeSummaries.format(selection.labels(), KnowledgeSelect.KEYWORDS));
    }

    public KeywordSelector selector() {
        return selector;
    }

    private static String cap(String text, int cap, boolean warn, String name, List<String> notes) {
        String body = text == null ? "" : text;
        if (body.length() > cap) {
            body = body.substring(0, cap);
            if (warn) {
                notes.add("Knowledge file '" + name + "' was truncated to " + cap + " characters.");
            }
        }
        return stripTrailingNewlines(body);
    }

    private List<String> included(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (String name : names) {
            if (name == null || labels.contains(name)) {
                continue;
            }
            String text = files.get(name);
            if (text != null && !text.isBlank()) {
                labels.add(name);
            }
        }
        return List.copyOf(labels);
    }

    private String wrap(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return OPEN + "\n" + content + "\n" + CLOSE;
    }

    public boolean contains(String name) {
        return name != null && files.containsKey(name);
    }

    /**
     * True when a prompt names a file that was not loaded and was not skipped as invalid UTF-8.
     * A non-UTF-8 file already logged its own warning and must not also be reported as unknown.
     */
    public boolean unknown(String name) {
        if (name == null || name.isBlank() || files.containsKey(name)) {
            return false;
        }
        return !invalidUtf8.contains(name);
    }

    public int size() {
        return files.size();
    }

    public List<String> names() {
        return List.copyOf(files.keySet());
    }

    /**
     * Delimited block for one request, truncated to {@code knowledge.max-chars}.
     * Unknown names are omitted here; the catalog logs those when prompts are loaded.
     * Truncation logs a single warning per distinct file list until the next reload.
     */
    public String block(List<String> names) {
        if (names == null || names.isEmpty() || files.isEmpty()) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String text = files.get(name);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append('[').append(name).append("]\n").append(text);
        }
        if (body.isEmpty()) {
            return "";
        }
        String content = body.toString();
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars);
            String key = String.join("\n", names);
            if (truncationWarned.add(key)) {
                logger.warning("Knowledge text was truncated to " + maxChars + " characters for this request.");
            }
        }
        return OPEN + "\n" + content + "\n" + CLOSE;
    }

    /**
     * Cache-key fragment. Empty when this prompt adds no knowledge text.
     * A change to the injected text changes the hash.
     */
    public String cacheToken(List<String> names) {
        return sha256(block(names));
    }

    public static String sha256(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static boolean isNotUtf8(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CharacterCodingException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                return false;
            }
            current = cause;
        }
        return false;
    }

    private static String stripTrailingNewlines(String text) {
        int end = text.length();
        while (end > 0) {
            char ch = text.charAt(end - 1);
            if (ch != '\n' && ch != '\r') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    public record Piece(String block, String cacheToken, String summary) {
        public Piece {
            block = block == null ? "" : block;
            cacheToken = cacheToken == null ? "" : cacheToken;
            summary = summary == null ? "" : summary;
        }
    }
}
