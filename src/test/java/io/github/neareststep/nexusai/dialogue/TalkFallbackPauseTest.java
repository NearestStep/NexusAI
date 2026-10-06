package io.github.neareststep.nexusai.dialogue;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.limit.RateLimiter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After HTTP 429 on a placeholder, {@code /nai talk} uses {@code fallback-model}
 * only when that model is a different provider that is not paused.
 */
class TalkFallbackPauseTest {

    @Test
    void placeholder429ThenTalkUsesADifferentUnpausedFallback() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = server(200, "from-fallback", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            assertEquals(1, queueHits.get());
            assertEquals(0, fallbackHits.get());
            assertTrue(harness.gate.isPaused());
            assertTrue(harness.gate.isProviderPaused("openai"));
            assertTrue(!harness.gate.isProviderPaused("groq"));

            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("from-fallback", result.text());
            assertEquals(1, queueHits.get());
            assertEquals(1, fallbackHits.get());
            assertTrue(harness.gate.isPaused());

            assertThrows(Exception.class, () -> harness.client.requestAsync("tip-again").join());
            assertEquals(1, queueHits.get());
            assertEquals(1, fallbackHits.get());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void placeholderThatAlreadyFailedOverStillSendsTheNextTalkToFallback() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = server(200, "from-fallback", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            String answer = harness.client.requestAsync(
                    "tip",
                    null,
                    GenerationOverrides.none().withFallbackModel("groq", "llama-3.3-70b-versatile"),
                    null
            ).join();
            assertEquals("from-fallback", answer);
            assertEquals(1, queueHits.get());
            assertEquals(1, fallbackHits.get());
            assertTrue(!harness.gate.isPaused());

            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("from-fallback", result.text());
            assertEquals(1, queueHits.get());
            assertEquals(2, fallbackHits.get());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void sameProviderFallbackStaysBusy() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Harness harness = harness(queue, queue, "openai", "gpt-4o", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals(1, queueHits.get());
        } finally {
            queue.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void pausedFallbackAlsoStaysBusy() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = server(429, "also", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals(1, queueHits.get());
            assertEquals(1, fallbackHits.get());
            assertTrue(harness.gate.isProviderPaused("openai"));
            assertTrue(harness.gate.isProviderPaused("groq"));
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void playerLimitStillBlocksTalkWhileAFallbackIsOpen() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = server(200, "from-fallback", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals(0, fallbackHits.get());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void unsolicitedToolCallsOnFallbackAreNotShown() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        String key = "sk-groq-2222";
        String raw = "&cRAW§l TOOLTEXT §§§ END §§§ " + key;
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = jsonServer(completion(raw, "qa_action"), fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            UUID player = UUID.randomUUID();
            TalkResult result = harness.engine.talk(talk(player, "hello"));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("...", result.text());
            assertFalse(result.text().contains(key));
            assertFalse(result.text().contains("TOOLTEXT"));
            assertFalse(result.text().contains("§"));
            String remembered = harness.memory.transcript(player, "blacksmith", 1_000L, 8, 8000, 0L).toString();
            assertFalse(remembered.contains(key));
            assertFalse(remembered.contains("TOOLTEXT"));
            assertFalse(remembered.contains("§"));
            assertEquals(1, fallbackHits.get());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void emptyFallbackReplyDoesNotCoolTheNextTurn() throws Exception {
        softFallbackDoesNotCool("&c§l");
    }

    @Test
    void markupOnlyFallbackReplyDoesNotCoolTheNextTurn() throws Exception {
        softFallbackDoesNotCool("<hover:show_text:'hello>world'>");
    }

    @Test
    void rejectedFallbackReplyDoesNotCoolTheNextTurn() throws Exception {
        softFallbackDoesNotCool("§§§ END §§§");
    }

    @Test
    void fallbackTriesTheNextKeyAfter401() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = keyedServer(fallbackHits, authorizations, 1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(
                    queue,
                    fallback,
                    "groq",
                    "llama-3.3-70b-versatile",
                    1_000,
                    executor,
                    List.of("sk-groq-key1-aaaa", "sk-groq-key2-bbbb"),
                    null);
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("second-key", result.text());
            assertEquals(1, queueHits.get());
            assertEquals(2, fallbackHits.get());
            assertEquals("Bearer sk-groq-key1-aaaa", authorizations.get(0));
            assertEquals("Bearer sk-groq-key2-bbbb", authorizations.get(1));
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void fallbackLogsTheFallback401InsteadOfTheQueue429() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = keyedServer(fallbackHits, new CopyOnWriteArrayList<>(), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        Logger failureLog = Logger.getLogger("talk-fallback-401-" + UUID.randomUUID());
        failureLog.setUseParentHandlers(false);
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        failureLog.addHandler(handler);
        try {
            Harness harness = harness(
                    queue,
                    fallback,
                    "groq",
                    "llama-3.3-70b-versatile",
                    1_000,
                    executor,
                    List.of("sk-groq-key1-aaaa", "sk-groq-key2-bbbb"),
                    failureLog);
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals(1, queueHits.get());
            assertEquals(2, fallbackHits.get());
            String sent = lines.stream().filter(line -> line.contains("Dialogue reply was not sent")).findFirst().orElse("");
            assertTrue(sent.contains("401"), sent);
            assertFalse(sent.contains("429"), sent);
        } finally {
            failureLog.removeHandler(handler);
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aDailyBudgetOnFallbackIsNamedBesideTheQueue429() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = server(200, "from-fallback", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        Logger failureLog = Logger.getLogger("talk-fallback-budget-" + UUID.randomUUID());
        failureLog.setUseParentHandlers(false);
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        failureLog.addHandler(handler);
        try {
            Harness harness = harness(
                    queue,
                    fallback,
                    "groq",
                    "llama",
                    1_000,
                    executor,
                    List.of("sk-groq-2222"),
                    failureLog,
                    List.of(
                            new QueueEntryConfig("openai", "gpt-4o-mini", 0),
                            new QueueEntryConfig("groq", "llama", 1)));
            assertTrue(harness.queue().tryConsume(1, System.currentTimeMillis()));
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals("", result.text());
            assertEquals("", result.error());
            assertEquals(1, queueHits.get());
            assertEquals(0, fallbackHits.get());
            String sent = lines.stream().filter(line -> line.contains("Dialogue reply was not sent")).findFirst().orElse("");
            assertTrue(sent.contains("openai / gpt-4o-mini: HTTP 429"), sent);
            assertTrue(sent.contains("groq / llama: daily request limit reached (1/1)"), sent);
            assertFalse(sent.substring(sent.indexOf("groq / llama:")).contains("429"), sent);
        } finally {
            failureLog.removeHandler(handler);
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aPlayerVendorKeyIsMaskedWhenStoredAndStaysInTheRequest() throws Exception {
        String slug = "sk-learn-pipeline-v2-2024-final";
        String key = "sk-qaUnconfigured9999zz";
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer queue = capturingServer(bodies);
        HttpServer fallback = server(200, "unused", new AtomicInteger());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            UUID player = UUID.randomUUID();
            TalkResult result = harness.engine.talk(talk(player, "see " + slug + " and " + key));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals(1, bodies.size());
            assertTrue(bodies.get(0).contains(key), bodies.get(0));
            assertTrue(bodies.get(0).contains(slug), bodies.get(0));
            String stored = harness.memory().transcript(player, "blacksmith", 1_000L, 8, 8000, 0L).get(0).text();
            assertTrue(stored.contains(slug), stored);
            assertTrue(stored.contains("****99zz"), stored);
            assertFalse(stored.contains(key), stored);
            Path dir = Files.createTempDirectory("talk-player-key");
            Path file = dir.resolve("dialogue-memory.yml");
            harness.memory().save(file.toFile(), null, false);
            String yaml = Files.readString(file);
            assertFalse(yaml.contains(key), yaml);
            assertTrue(yaml.contains(slug), yaml);
            assertTrue(yaml.contains("****99zz"), yaml);
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void everyDailyCapLogsTheExhaustedQueueOnce() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(200, "unused", queueHits);
        HttpServer fallback = server(200, "unused", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Logger failureLog = Logger.getLogger("talk-daily-only-" + UUID.randomUUID());
        failureLog.setUseParentHandlers(false);
        List<String> lines = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        failureLog.addHandler(handler);
        try {
            Harness harness = harness(
                    queue,
                    fallback,
                    "groq",
                    "llama",
                    1_000,
                    executor,
                    List.of("sk-groq-2222"),
                    failureLog,
                    List.of(
                            new QueueEntryConfig("openai", "gpt-4o-mini", 1),
                            new QueueEntryConfig("groq", "llama", 1)));
            long now = System.currentTimeMillis();
            assertTrue(harness.queue().tryConsume(0, now));
            assertTrue(harness.queue().tryConsume(1, now));
            TalkResult first = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            TalkResult second = harness.engine.talk(talk(UUID.randomUUID(), "again"));
            assertEquals(TalkCode.BUSY, first.code());
            assertEquals("", first.text());
            assertEquals(TalkCode.BUSY, second.code());
            assertEquals(0, queueHits.get());
            assertEquals(0, fallbackHits.get());
            List<String> sent = lines.stream().filter(line -> line.contains("Dialogue reply was not sent")).toList();
            assertEquals(1, sent.size(), lines.toString());
            assertTrue(sent.get(0).contains("All model-queue entries are exhausted"), sent.get(0));
        } finally {
            failureLog.removeHandler(handler);
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aGenericQueueErrorDoesNotSendTalkToFallback() throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(500, "down", queueHits);
        HttpServer fallback = server(200, "from-fallback", fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            TalkResult result = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            assertEquals(TalkCode.BUSY, result.code());
            assertEquals(0, fallbackHits.get());
            assertTrue(!harness.gate.isPaused());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    private void softFallbackDoesNotCool(String content) throws Exception {
        AtomicInteger queueHits = new AtomicInteger();
        AtomicInteger fallbackHits = new AtomicInteger();
        HttpServer queue = server(429, "nope", queueHits);
        HttpServer fallback = jsonServer(completion(content, null), fallbackHits);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Harness harness = harness(queue, fallback, "groq", "llama-3.3-70b-versatile", 1_000, executor);
            assertThrows(Exception.class, () -> harness.client.requestAsync("tip").join());
            TalkResult first = harness.engine.talk(talk(UUID.randomUUID(), "hello"));
            TalkResult second = harness.engine.talk(talk(UUID.randomUUID(), "again"));
            assertEquals(TalkCode.REPLY, first.code());
            assertEquals("...", first.text());
            assertEquals(TalkCode.REPLY, second.code());
            assertEquals("...", second.text());
            assertEquals(1, queueHits.get());
            assertEquals(2, fallbackHits.get());
        } finally {
            queue.stop(0);
            fallback.stop(0);
            executor.shutdownNow();
        }
    }

    private static String completion(String content, String toolName) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        String tools = toolName == null
                ? ""
                : ",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\""
                + toolName + "\",\"arguments\":\"{}\"}}]";
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"" + tools + "}}]}";
    }

    private static HttpServer capturingServer(List<String> bodies) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer server(int status, String text, AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            byte[] body = status == 200
                    ? ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : ("{\"error\":{\"message\":\"" + text + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer jsonServer(String json, AtomicInteger hits) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * @param successes how many leading calls return HTTP 401 before a 200. Zero means every call is 401.
     */
    private static HttpServer keyedServer(AtomicInteger hits, List<String> authorizations, int failuresBeforeSuccess)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int n = hits.incrementAndGet();
            boolean ok = failuresBeforeSuccess > 0 && n > failuresBeforeSuccess;
            byte[] body = (ok
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"second-key\"}}]}"
                    : "{\"error\":{\"message\":\"unauthorized\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Harness harness(
            HttpServer queue,
            HttpServer fallback,
            String fallbackProvider,
            String fallbackModel,
            int perMinute,
            ExecutorService executor
    ) {
        return harness(
                queue, fallback, fallbackProvider, fallbackModel, perMinute, executor, List.of("sk-groq-2222"), null,
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)));
    }

    private static Harness harness(
            HttpServer queue,
            HttpServer fallback,
            String fallbackProvider,
            String fallbackModel,
            int perMinute,
            ExecutorService executor,
            List<String> groqKeys,
            Logger failureLog
    ) {
        return harness(
                queue, fallback, fallbackProvider, fallbackModel, perMinute, executor, groqKeys, failureLog,
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)));
    }

    private static Harness harness(
            HttpServer queue,
            HttpServer fallback,
            String fallbackProvider,
            String fallbackModel,
            int perMinute,
            ExecutorService executor,
            List<String> groqKeys,
            Logger failureLog,
            List<QueueEntryConfig> rows
    ) {
        PluginConfig config = config(queue, fallback, fallbackProvider, fallbackModel, groqKeys);
        Logger logger = Logger.getLogger("talk-fallback-" + queue.getAddress().getPort());
        ModelQueue modelQueue = new ModelQueue(
                rows,
                0,
                60_000L,
                300_000L,
                null,
                logger);
        RequestGate gate = new RequestGate(
                new RateLimiter(perMinute, 10_000),
                0L,
                0L,
                60_000L,
                60_000L,
                System::currentTimeMillis);
        OpenAiProvider http = new OpenAiProvider(config, executor, logger);
        RoutingProvider routing = new RoutingProvider(
                config, modelQueue, http, executor, logger);
        AiHttpClient client = new AiHttpClient(
                new AiCache(Duration.ofSeconds(300), 10),
                routing,
                config,
                gate,
                new io.github.neareststep.nexusai.ai.AiDiagnostics(logger, Duration.ofSeconds(30)),
                logger);
        DialogueRouter router = new DialogueRouter(
                ignored -> config,
                ignored -> modelQueue,
                routing::sharedRing,
                new DialogueTransport(() -> config, java.net.http.HttpClient.newHttpClient()),
                admission(gate),
                logger,
                System::currentTimeMillis);
        MemoryStore memory = new MemoryStore();
        DialogueEngine engine = failureLog == null
                ? new DialogueEngine(
                memory,
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                router::route,
                (id, action, command) -> "ran",
                ActionLog.noop(),
                ZoneId.of("UTC"))
                : new DialogueEngine(
                memory,
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                router::route,
                (id, action, command) -> "ran",
                ActionLog.noop(),
                ZoneId.of("UTC"),
                null,
                failureLog,
                config::configuredSecrets);
        return new Harness(client, engine, gate, memory, modelQueue);
    }

    private static DialogueRouter.Admission admission(RequestGate gate) {
        return new DialogueRouter.Admission() {
            @Override
            public Optional<String> admit(UUID playerId, String key) {
                return gate.tryAdmit(playerId, key, false);
            }

            @Override
            public void success(String key) {
                gate.recordSuccess(key, gate.pauseStamp(), gate.failureEpoch(key), true);
            }

            @Override
            public void failure(String key, Throwable error) {
                AiErrorKind kind = AiErrors.classify(error);
                AiRequestException typed = AiErrors.find(error);
                long retry = typed == null ? 0L : typed.retryAfterSeconds();
                gate.recordFailure(key, kind, retry, true, AiRequestException.pausedProvidersOf(error));
            }

            @Override
            public boolean providerPauseActive() {
                return gate.isPaused();
            }

            @Override
            public boolean pauseIsGlobal() {
                return gate.pauseIsGlobal();
            }

            @Override
            public boolean providerPaused(String providerId) {
                return gate.isProviderPaused(providerId);
            }

            @Override
            public Optional<String> admitIgnoringPause(UUID playerId, String key) {
                return gate.tryAdmitIgnoringPause(playerId, key);
            }

            @Override
            public void successKeepingPause(String key) {
                gate.recordSuccess(key, gate.pauseStamp(), gate.failureEpoch(key), false);
            }
        };
    }

    private static PluginConfig config(
            HttpServer queue,
            HttpServer fallback,
            String fallbackProvider,
            String fallbackModel
    ) {
        return config(queue, fallback, fallbackProvider, fallbackModel, List.of("sk-groq-2222"));
    }

    private static PluginConfig config(
            HttpServer queue,
            HttpServer fallback,
            String fallbackProvider,
            String fallbackModel,
            List<String> groqKeys
    ) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "sk-openai-1111");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 32);
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.requests-per-minute", 1000);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "http://127.0.0.1:" + queue.getAddress().getPort() + "/v1");
        yaml.set("providers.openai.api-key", "sk-openai-1111");
        yaml.createSection("providers.groq");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "http://127.0.0.1:" + fallback.getAddress().getPort() + "/v1");
        yaml.set("providers.groq.api-key", groqKeys);
        yaml.set("model-queue", List.of(java.util.Map.of("provider", "openai", "model", "gpt-4o-mini")));
        yaml.set("fallback-model.provider", fallbackProvider);
        yaml.set("fallback-model.model", fallbackModel);
        return new PluginConfig(yaml);
    }

    private static DialogueEngine.TalkRequest talk(UUID player, String message) {
        return new DialogueEngine.TalkRequest(
                player,
                "Steve",
                "blacksmith",
                message,
                false,
                false,
                false,
                "You are Bram.",
                "...",
                DialogueProfile.absent(),
                List.of(),
                new DialogueSettings(true, 8, false, 8000, 0, 0, 0, 12, 0, 0, 200, false, 300, false, false, 1),
                GenerationOverrides.none(),
                "chat",
                "world",
                0,
                64,
                0,
                node -> true,
                1_000L);
    }

    private record Harness(
            AiHttpClient client,
            DialogueEngine engine,
            RequestGate gate,
            MemoryStore memory,
            ModelQueue queue
    ) {
    }
}
