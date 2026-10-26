package io.github.neareststep.nexusai.text;

import io.github.neareststep.nexusai.ai.ModelAnswer;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.YamlStrings;
import io.github.neareststep.nexusai.dialogue.ActionLog;
import io.github.neareststep.nexusai.moderation.ModerationLog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NAI-132: a lone UTF-16 surrogate in a model reply, or in text that ends up in a data file, is
 * stored and written as U+FFFD. UTF-8 cannot encode a lone surrogate, so the write would fail.
 */
class LoneSurrogateWritersTest {

    private static final String HALF = "\uD83D";

    @Test
    void modelReplyIsCleanedBeforeItReachesTheCacheOrThePool() {
        assertEquals("hi \uFFFD", ModelAnswer.text("hi " + HALF).text());
        assertEquals("\uFFFD there", new ModelAnswer("\uDE00 there", null).text());
        String clean = "hi \uD83D\uDE00";
        assertSame(clean, ModelAnswer.text(clean).text());
    }

    @Test
    void cacheStoresALoneSurrogateAsAReplacementCharacter() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        cache.put("key", "cached " + HALF);
        assertEquals("cached \uFFFD", cache.get("key").orElseThrow());
        assertEquals("x\uFFFD", new AiCache.CachedAnswer("x\uDC00", "", "").text());
    }

    @Test
    void yamlQuoteWritesALoneSurrogateAsAReplacementCharacter() {
        assertEquals("\"a\uFFFDb\"", YamlStrings.quote("a" + HALF + "b"));
        assertEquals("\"\uFFFD\uFFFD\"", YamlStrings.quote("\uDE00" + HALF));
        assertEquals("\"\uD83D\uDE00 \\\"q\\\" \\\\ \\n\\r\\t\"", YamlStrings.quote("\uD83D\uDE00 \"q\" \\ \n\r\t"));
    }

    @Test
    void moderationLogLineWithALoneSurrogateIsWritten() throws Exception {
        Path file = Files.createTempDirectory("nexusai-moderation-lone").resolve("moderation.log");
        List<LogRecord> records = new ArrayList<>();
        ModerationLog log = new ModerationLog(file.toFile(), logger(records));
        log.append(UUID.randomUUID(), "Steve", "hello", "spam", "reason " + HALF);
        log.append(UUID.randomUUID(), "Alex", "second " + HALF, "spam", "clean");

        assertEquals(List.of(), warnings(records));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).endsWith(" | Steve | " + lines.get(0).split(" \\| ")[2] + " | spam | reason \uFFFD | hello"),
                lines.get(0));
        assertTrue(lines.get(1).endsWith(" | spam | clean | second \uFFFD"), lines.get(1));
    }

    @Test
    void actionLogLineWithALoneSurrogateIsWritten() throws Exception {
        Path file = Files.createTempDirectory("nexusai-actions-lone").resolve("actions.log");
        List<LogRecord> records = new ArrayList<>();
        ActionLog log = new ActionLog(logger(records), file.toFile(), () -> true);
        log.record("Steve", "smith", "give " + HALF, "ok");

        assertEquals(List.of(), warnings(records));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).endsWith(" action player=Steve character=smith action=give \uFFFD result=ok"), lines.get(0));
    }

    /**
     * A fallback model name with a lone surrogate (from a request, or escaped in usage.yml) made
     * every usage.yml save throw. Now it is written as U+FFFD and the counters are kept.
     */
    @Test
    void usageFileWithALoneSurrogateInAModelNameIsWritten() throws Exception {
        Path dir = Files.createTempDirectory("nexusai-usage-lone");
        Path file = dir.resolve("usage.yml");
        List<LogRecord> records = new ArrayList<>();
        ModelQueue queue = new ModelQueue(List.of(), 0, 1_000L, 1_000L, file.toFile(), logger(records));
        queue.recordFallbackRejection("openai", "model " + HALF);
        queue.recordFallbackRejection("openai", "model " + HALF);

        assertEquals(List.of(), warnings(records));
        assertTrue(Files.isRegularFile(file));
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(saved.contains("model \uFFFD"), saved);
        assertTrue(saved.contains("rejected: 2"), saved);
        assertFalse(saved.contains("\\ud83d") || saved.contains("\\uD83D"), saved);

        // Escaped in the file: the next save writes U+FFFD instead of failing.
        String escaped = saved.replaceAll("(?m)^(\\s*model: ).*$", "$1\"model \\\\ud83d\"");
        assertTrue(escaped.contains("model: \"model \\ud83d\""), escaped);
        Files.writeString(file, escaped, StandardCharsets.UTF_8);
        List<LogRecord> again = new ArrayList<>();
        ModelQueue reloaded = new ModelQueue(List.of(), 0, 1_000L, 1_000L, file.toFile(), logger(again));
        reloaded.recordFallbackRejection("openai", "other");
        assertEquals(List.of(), warnings(again));
        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(rewritten.contains("other"), rewritten);
        assertTrue(rewritten.contains("model \uFFFD"), rewritten);
    }

    private static List<String> warnings(List<LogRecord> records) {
        synchronized (records) {
            return records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    private static Logger logger(List<LogRecord> records) {
        Logger logger = Logger.getLogger("lone-writers-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                synchronized (records) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }
}
