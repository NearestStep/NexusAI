package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelQueueTest {

    @Test
    void switchesOnDailyLimitHeadersAndPersistsUntilMidnight() throws Exception {
        Path file = Files.createTempDirectory("nexusai-usage").resolve("usage.yml");
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 9, 29));
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("queue-test-" + file.getFileName());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        ZoneId zone = ZoneId.of("UTC");
        ModelQueue queue = queue(file, day, logger, zone, 5);
        assertEquals("openai", queue.select(1_000L).orElseThrow().provider());
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("at least 80% of the daily limit")));
        assertEquals("ACTIVE", queue.status(1_000L).getFirst().state());
        assertTrue(queue.tryConsume(0, 1_000L));
        assertEquals("groq", queue.select(1_000L).orElseThrow().provider());
        assertTrue(queue.status(1_000L).getFirst().state().startsWith("LIMIT REACHED (5/5)"));

        queue.observe(1, Map.of(
                "x-ratelimit-remaining-requests", List.of("0"),
                "x-ratelimit-reset-requests", List.of("30s")
        ), 2_000L);
        assertTrue(queue.status(2_000L).get(1).state().startsWith("COOLDOWN until "));
        assertEquals("gemini", queue.select(2_000L).orElseThrow().provider());
        assertEquals("ACTIVE", queue.status(3_000L).get(2).state());

        queue.markFailure(2, new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429", null, 10L), 3_000L);
        assertTrue(queue.select(3_000L).isEmpty());

        ModelQueue reloaded = queue(file, day, logger, zone, 5);
        assertEquals(5, reloaded.requestsToday(0));
        assertEquals(5, reloaded.providerRequests("openai"));
        assertTrue(reloaded.select(4_000L).isEmpty() || !reloaded.select(4_000L).orElseThrow().provider().equals("openai"));

        day.set(LocalDate.of(2026, 9, 30));
        assertEquals("openai", reloaded.select(5_000L).orElseThrow().provider());
        assertEquals(0, reloaded.requestsToday(0));
    }

    @Test
    void aRejectedAnswerDoesNotCoolTheRow() throws Exception {
        ModelQueue queue = queue(
                Files.createTempDirectory("nexusai-usage").resolve("usage.yml"),
                new AtomicReference<>(LocalDate.of(2026, 9, 29)),
                Logger.getLogger("queue-reject"),
                ZoneId.of("UTC"),
                0);
        queue.recordRejection(0);
        queue.recordRejection(0);
        assertEquals(2, queue.status(1_000L).getFirst().rejected());
        assertEquals("ACTIVE", queue.status(1_000L).getFirst().state());
        assertEquals("openai", queue.select(1_000L).orElseThrow().provider());
    }

    @Test
    void rejectedCountPersistsAcrossReloadAndResetsAtMidnight() throws Exception {
        Path file = Files.createTempDirectory("nexusai-usage-rejected").resolve("usage.yml");
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 9, 29));
        Logger logger = Logger.getLogger("queue-rejected-persist");
        ZoneId zone = ZoneId.of("UTC");
        ModelQueue queue = queue(file, day, logger, zone, 0);
        assertTrue(queue.tryConsume(0, 1_000L));
        queue.recordRejection(0);
        queue.recordRejection(0);
        queue.recordRejection(1);
        assertEquals(1, queue.requestsToday(0));
        assertEquals(2, queue.status(1_000L).get(0).rejected());
        assertEquals(1, queue.status(1_000L).get(1).rejected());

        ModelQueue reloaded = queue(file, day, logger, zone, 0);
        assertEquals(1, reloaded.requestsToday(0));
        assertEquals(2, reloaded.status(1_000L).get(0).rejected());
        assertEquals(1, reloaded.status(1_000L).get(1).rejected());

        day.set(LocalDate.of(2026, 9, 30));
        assertEquals(0, reloaded.requestsToday(0));
        assertEquals(0, reloaded.status(2_000L).get(0).rejected());
        assertEquals(0, reloaded.status(2_000L).get(1).rejected());

        ModelQueue nextDay = queue(file, day, logger, zone, 0);
        assertEquals(0, nextDay.requestsToday(0));
        assertEquals(0, nextDay.status(2_000L).get(0).rejected());
    }

    @Test
    void aSingleHttpFailureStaysThatProvidersMessage() {
        ModelQueue queue = timedQueue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), 0);
        queue.markFailure(0, new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 from 127.0.0.1", null), 1_000L);
        AiRequestException error = queue.explain(null, 1_000L);
        assertTrue(error.getMessage().startsWith("HTTP 429 from 127.0.0.1"), error.getMessage());
        assertFalse(error.getMessage().contains("openai /"), error.getMessage());
        assertEquals(AiErrorKind.RATE_LIMIT, error.kind());
    }

    @Test
    void explainNamesADailyLimitApartFromAStored429() {
        ModelQueue queue = timedQueue(List.of(
                new QueueEntryConfig("openai", "gpt-4o-mini", 0),
                new QueueEntryConfig("groq", "llama", 1)
        ), 0);
        assertTrue(queue.tryConsume(1, 1_000L));
        queue.markFailure(0, new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 from 127.0.0.1", null), 1_000L);
        AiRequestException error = queue.explain(null, 1_000L);
        String message = error.getMessage();
        assertEquals(AiErrorKind.RATE_LIMIT, error.kind());
        assertTrue(message.contains("openai / gpt-4o-mini: HTTP 429 from 127.0.0.1"), message);
        assertTrue(message.contains("groq / llama: daily request limit reached (1/1)"), message);
        assertFalse(message.substring(message.indexOf("groq / llama:")).contains("429"), message);
        assertTrue(message.contains("Retry after"), message);
    }

    @Test
    void explainNamesADedicatedFallbackTokenBudgetApartFromTheQueue429() {
        ModelQueue queue = timedQueue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), 0);
        queue.markFailure(0, new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 from 127.0.0.1", null), 1_000L);
        queue.observeFallback("groq", "llama", Map.of(
                "x-ratelimit-remaining-tokens", List.of("0"),
                "x-ratelimit-reset-tokens", List.of("30")
        ), 2_000L);
        AiRequestException passed = new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 from 127.0.0.1", null);
        AiRequestException error = queue.explain(passed, 5_000L);
        String message = error.getMessage();
        assertEquals(AiErrorKind.RATE_LIMIT, error.kind());
        assertTrue(message.contains("openai / gpt-4o-mini: HTTP 429 from 127.0.0.1"), message);
        assertTrue(message.contains("groq / llama: token budget exhausted"), message);
        assertFalse(message.substring(message.indexOf("groq / llama:")).contains("429"), message);
    }

    private static ModelQueue timedQueue(List<QueueEntryConfig> rows, int threshold) {
        return new ModelQueue(
                rows,
                threshold,
                60_000L,
                300_000L,
                null,
                () -> 5_000L,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("queue-explain")
        );
    }

    private static ModelQueue queue(Path file, AtomicReference<LocalDate> day, Logger logger, ZoneId zone, int limit) {
        return new ModelQueue(
                List.of(
                        new QueueEntryConfig("openai", "gpt-4o-mini", limit),
                        new QueueEntryConfig("groq", "llama", 0),
                        new QueueEntryConfig("gemini", "gemini-flash", 0)
                ),
                0,
                60_000L,
                300_000L,
                file.toFile(),
                () -> 0L,
                day::get,
                zone,
                logger
        );
    }
}
