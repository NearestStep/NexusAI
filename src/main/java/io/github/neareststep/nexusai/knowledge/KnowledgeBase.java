package io.github.neareststep.nexusai.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
              Text outside this comment is sent to the model. Replace it with your server lore.
            -->
            """;

    private final Map<String, String> files;
    private final int maxChars;
    private final Logger logger;
    private final Set<String> truncationWarned = ConcurrentHashMap.newKeySet();

    private KnowledgeBase(Map<String, String> files, int maxChars, Logger logger) {
        this.files = files;
        this.maxChars = Math.max(1, maxChars);
        this.logger = logger == null ? Logger.getLogger("nexusai.knowledge") : logger;
    }

    public static KnowledgeBase empty() {
        return new KnowledgeBase(Map.of(), 6000, null);
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
        List<String> notes = warnings == null ? new ArrayList<>() : warnings;
        int fileCap = Math.max(1, maxFileChars);
        Map<String, String> loaded = new LinkedHashMap<>();
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
                        notes.add("Could not read knowledge file '" + fileName + "'.");
                        if (logger != null) {
                            logger.log(Level.WARNING, "Could not read knowledge file " + fileName, e);
                        }
                        continue;
                    }
                    if (text.length() > fileCap) {
                        text = text.substring(0, fileCap);
                        notes.add("Knowledge file '" + name + "' was truncated to " + fileCap + " characters.");
                    }
                    String stripped = stripTrailingNewlines(text);
                    if (!stripped.isBlank()) {
                        loaded.put(name, stripped);
                    }
                }
            } catch (IOException e) {
                notes.add("Could not list the knowledge folder.");
                if (logger != null) {
                    logger.log(Level.WARNING, "Could not list the knowledge folder", e);
                }
            }
        }
        return new KnowledgeBase(Map.copyOf(loaded), maxChars, logger);
    }

    public boolean contains(String name) {
        return name != null && files.containsKey(name);
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
}
