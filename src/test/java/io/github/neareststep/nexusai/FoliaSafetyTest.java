package io.github.neareststep.nexusai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when {@code src/main/java} calls the Bukkit scheduler APIs Folia rejects.
 * Comments are not calls. A call is exempt only on a line with an explicit
 * allowlist comment, {@code // folia-safety-allow: <reason>}, and the reason
 * must be non-blank. The same marker works after {@code /*}. Load-test sources
 * are not scanned.
 */
class FoliaSafetyTest {

    @Test
    void mainSourcesHaveNoForbiddenSchedulerCalls() throws Exception {
        Path main = mainJava();
        List<String> hits = scanTree(main);
        assertTrue(hits.isEmpty(), String.join("\n", hits));
    }

    @Test
    void loadTestSourcesAreNotScanned() throws Exception {
        Path main = mainJava();
        assertTrue(main.endsWith(Path.of("src", "main", "java")));
        try (Stream<Path> walk = Files.walk(main)) {
            assertTrue(walk.map(Path::toString).noneMatch(path -> path.contains("loadtest") || path.contains("LoadDriver")));
        }
        Path load = Path.of("src/loadtest");
        assertTrue(Files.isDirectory(load));
        assertFalse(main.startsWith(load.toAbsolutePath().normalize()));
    }

    @Test
    void eachForbiddenPatternIsReported() {
        String source = """
                class Sample {
                    void go(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> {});
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {}, 1L);
                        new BukkitRunnable() { public void run() {} }.runTaskTimer(plugin, 1L, 1L);
                        BukkitScheduler scheduler = Bukkit.getScheduler();
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {});
                    }
                }
                """;
        List<String> hits = violationsIn("Sample.java", source);
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("Bukkit.getScheduler")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("getServer().getScheduler")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("BukkitRunnable")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("BukkitScheduler")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("runTask")), hits.toString());
    }

    @Test
    void safeRegionSchedulersAreAllowed() {
        String source = """
                class Sample {
                    void go(Plugin plugin, Entity entity) {
                        entity.getScheduler().run(plugin, task -> {}, null);
                        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {});
                        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {}, 20L, 20L);
                    }
                }
                """;
        assertTrue(violationsIn("Sample.java", source).isEmpty());
    }

    @Test
    void commentsAndJavadocAreNotCode() {
        String source = """
                class Sample {
                    /**
                     * Folia rejects {@code Bukkit.getScheduler()}.
                     * {@code getServer().getScheduler()}, {@code BukkitRunnable},
                     * {@code BukkitScheduler}, and {@code runTask} are rejected too.
                     */
                    void go() {
                        // Bukkit.getScheduler().runTaskTimer(plugin, () -> {}, 1L, 1L);
                        /* new BukkitRunnable() { } */
                    }
                }
                """;
        assertTrue(violationsIn("Sample.java", source).isEmpty());
    }

    @Test
    void allowlistCommentExemptsOnlyThatLine() {
        String allowed = """
                class Sample {
                    void go(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> {}); // folia-safety-allow: paper-only sample
                    }
                }
                """;
        assertTrue(violationsIn("Sample.java", allowed).isEmpty());

        String block = """
                class Sample {
                    void go(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> {}); /* folia-safety-allow: paper-only sample */
                    }
                }
                """;
        assertTrue(violationsIn("Sample.java", block).isEmpty());

        String nextLine = """
                class Sample {
                    void go(Plugin plugin) {
                        // folia-safety-allow: this comment is not on the call
                        Bukkit.getScheduler().runTask(plugin, () -> {});
                    }
                }
                """;
        assertFalse(violationsIn("Sample.java", nextLine).isEmpty());

        String blankReason = """
                class Sample {
                    void go(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> {}); // folia-safety-allow:
                    }
                }
                """;
        assertFalse(violationsIn("Sample.java", blankReason).isEmpty());
    }

    @Test
    void patternSplitAcrossLinesIsReported() {
        String source = """
                class Sample {
                    void go(Plugin plugin) {
                        plugin.getServer()
                                .getScheduler()
                                .runTask(plugin, () -> {});
                    }
                }
                """;
        List<String> hits = violationsIn("Sample.java", source);
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("getServer().getScheduler")), hits.toString());
        assertTrue(hits.stream().anyMatch(hit -> hit.contains("runTask")), hits.toString());
    }

    @Test
    void stringLiteralIsReported() {
        String source = """
                class Sample {
                    String name = "Bukkit.getScheduler";
                }
                """;
        assertFalse(violationsIn("Sample.java", source).isEmpty());
    }

    private static Path mainJava() {
        Path main = Path.of("src/main/java").toAbsolutePath().normalize();
        if (!Files.isDirectory(main)) {
            throw new IllegalStateException("src/main/java not found from " + Path.of("").toAbsolutePath());
        }
        return main;
    }

    private static List<String> scanTree(Path root) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                hits.addAll(violationsIn(relative, Files.readString(file)));
            }
        }
        return hits;
    }

    static List<String> violationsIn(String path, String source) {
        String[] rawLines = source.split("\n", -1);
        String[] codeLines = new String[rawLines.length];
        ScanState state = new ScanState();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].endsWith("\r") ? rawLines[i].substring(0, rawLines[i].length() - 1) : rawLines[i];
            boolean allowlisted = !state.block && !state.text && isAllowlisted(line);
            String stripped = stripLine(line, state);
            codeLines[i] = allowlisted ? "" : stripped;
        }
        String joined = String.join("\n", codeLines);
        List<String> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(joined);
            while (matcher.find()) {
                int line = lineNumber(joined, matcher.start());
                hits.add(path + ":" + line + ": " + rule.name);
            }
        }
        return hits;
    }

    private static int lineNumber(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The marker must sit in a comment on the same line, and the reason after the colon
     * must contain a non-space character.
     */
    private static boolean isAllowlisted(String line) {
        int marker = line.indexOf(ALLOWLIST);
        if (marker < 0) {
            return false;
        }
        String reason = line.substring(marker + ALLOWLIST.length()).trim();
        if (reason.endsWith("*/")) {
            reason = reason.substring(0, reason.length() - 2).trim();
        }
        if (reason.isEmpty()) {
            return false;
        }
        String before = line.substring(0, marker);
        return before.contains("//") || before.contains("/*") || before.stripLeading().startsWith("*");
    }

    private static String stripLine(String line, ScanState state) {
        StringBuilder code = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char current = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (state.text) {
                int close = line.indexOf("\"\"\"", i);
                if (close < 0) {
                    code.append(line.substring(i));
                    return code.toString();
                }
                code.append(line, i, close + 3);
                state.text = false;
                i = close + 3;
                continue;
            }
            if (state.block) {
                if (current == '*' && next == '/') {
                    state.block = false;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            if (current == '/' && next == '/') {
                break;
            }
            if (current == '/' && next == '*') {
                state.block = true;
                i += 2;
                continue;
            }
            if (current == '"' && next == '"' && i + 2 < line.length() && line.charAt(i + 2) == '"') {
                state.text = true;
                code.append("\"\"\"");
                i += 3;
                continue;
            }
            if (current == '"' || current == '\'') {
                i = appendQuoted(line, i, current, code);
                continue;
            }
            code.append(current);
            i++;
        }
        return code.toString();
    }

    private static int appendQuoted(String line, int start, char quote, StringBuilder code) {
        code.append(quote);
        int i = start + 1;
        while (i < line.length()) {
            char current = line.charAt(i);
            code.append(current);
            if (current == '\\' && i + 1 < line.length()) {
                code.append(line.charAt(i + 1));
                i += 2;
                continue;
            }
            i++;
            if (current == quote) {
                break;
            }
        }
        return i;
    }

    private static final String ALLOWLIST = "folia-safety-allow:";

    private static final List<Rule> RULES = List.of(
            new Rule("Bukkit.getScheduler", Pattern.compile("(?<![A-Za-z0-9_])Bukkit\\s*\\.\\s*getScheduler\\b")),
            new Rule("getServer().getScheduler", Pattern.compile("getServer\\s*\\(\\s*\\)\\s*\\.\\s*getScheduler\\b")),
            new Rule("BukkitRunnable", Pattern.compile("(?<![A-Za-z0-9_])BukkitRunnable\\b")),
            new Rule("BukkitScheduler", Pattern.compile("(?<![A-Za-z0-9_])BukkitScheduler\\b")),
            new Rule("runTask", Pattern.compile("(?<![A-Za-z0-9_])runTask"))
    );

    private record Rule(String name, Pattern pattern) {
    }

    private static final class ScanState {
        private boolean block;
        private boolean text;
    }
}
