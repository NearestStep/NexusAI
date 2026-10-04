package io.github.neareststep.nexusai.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpGate;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.config.QueueStrategy;
import io.github.neareststep.nexusai.limit.RateLimiter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueRouterSummaryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID player = UUID.randomUUID();

    @Test
    void fourRepliesSendOneSummaryAndTheNextSystemWrapsIt() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"They asked about the harbor.\"}}]}");
        try {
            Fixture fixture = fixture(server, List.of(row("gpt-4o-mini", 0)), admitting());
            DialogueEngine engine = engine(fixture, Runnable::run);
            DialogueSettings settings = summarySettings(2, 2);
            for (int i = 0; i < 4; i++) {
                TalkResult result = engine.talk(talk("m" + i, settings, 10_000L + i));
                assertEquals(TalkCode.REPLY, result.code());
                assertEquals("They asked about the harbor.", result.text());
            }
            long summaries = bodies.stream().filter(DialogueRouterSummaryTest::summaryBody).count();
            assertEquals(1L, summaries);
            assertEquals(5, bodies.size());
            assertEquals(5, fixture.queue.requestsToday(0));
            engine.talk(talk("next", settings, 20_000L));
            String next = bodies.get(bodies.size() - 1);
            JsonNode system = mapper.readTree(next).get("messages").get(0).get("content");
            String text = system.asText();
            assertTrue(text.contains(DialogueSummary.HEADER.trim()));
            assertTrue(text.contains(PlayerInput.OPEN));
            assertTrue(text.contains("They asked about the harbor."));
            assertTrue(text.contains(PlayerInput.CLOSE));
            assertTrue(text.contains(PlayerInput.GUARD));
            assertEquals(1L, bodies.stream().filter(DialogueRouterSummaryTest::summaryBody).count());
            JsonNode summary = mapper.readTree(bodies.stream().filter(DialogueRouterSummaryTest::summaryBody).findFirst().orElseThrow());
            assertEquals(200, summary.get("max_tokens").asInt());
            assertTrue(summary.get("messages").get(1).get("content").asText().contains(PlayerInput.OPEN));
            assertTrue(summary.get("messages").get(0).get("content").asText().contains(PlayerInput.GUARD));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summaryThatEchoesAKeyIsStoredMasked() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, (exchange, body) -> {
            if (body.contains("third person")) {
                return "{\"choices\":[{\"message\":{\"content\":\"The smith kept test-key in the notes.\"}}]}";
            }
            return "{\"choices\":[{\"message\":{\"content\":\"Hello.\"}}]}";
        });
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("nai-summary");
        try {
            Fixture fixture = fixture(server, List.of(row("gpt-4o-mini", 0)), admitting());
            MemoryStore memory = new MemoryStore();
            DialogueEngine engine = engine(fixture, memory, new SummaryStats(ZoneId.of("UTC")), Runnable::run);
            DialogueSettings settings = summarySettings(2, 2);
            for (int i = 0; i < 4; i++) {
                assertEquals(TalkCode.REPLY, engine.talk(talk("m" + i, settings, 10_000L + i)).code());
            }
            String stored = memory.summary(player, "blacksmith", 20_000L, 0L);
            assertTrue(stored.contains("****-key"), stored);
            assertFalse(stored.contains("test-key"), stored);
            java.io.File file = dir.resolve("dialogue-memory.yml").toFile();
            memory.save(file, fixture.logger, true);
            String yaml = java.nio.file.Files.readString(file.toPath());
            assertTrue(yaml.contains("****-key"), yaml);
            assertFalse(yaml.contains("test-key"), yaml);
            engine.talk(talk("next", settings, 20_000L));
            String next = bodies.get(bodies.size() - 1);
            assertTrue(next.contains("****-key"), next);
            assertFalse(next.contains("test-key"), next);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledDialogueBodyHasNoSummary() throws Exception {
        List<String> off = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        HttpServer server = server(off, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        HttpServer other = server(defaults, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        try {
            DialogueSettings explicit = DialogueSettings.read(summaryYaml(false, 2, 2));
            DialogueSettings plain = new DialogueSettings(
                    true, 2, false, 8000, 0, 0, 0, 12, 0, 0, 200, false, 300, false, false, 1);
            DialogueEngine offEngine = engine(fixture(server, List.of(row("gpt-4o-mini", 0)), admitting()), Runnable::run);
            DialogueEngine plainEngine = engine(fixture(other, List.of(row("gpt-4o-mini", 0)), admitting()), Runnable::run);
            for (int i = 0; i < 3; i++) {
                offEngine.talk(talk("m" + i, explicit, 5_000L + i));
                plainEngine.talk(talk("m" + i, plain, 5_000L + i));
            }
            assertEquals(off.size(), defaults.size());
            for (int i = 0; i < off.size(); i++) {
                assertEquals(normalizePort(defaults.get(i)), normalizePort(off.get(i)));
                assertFalse(off.get(i).contains("Summary of earlier conversation"));
            }
        } finally {
            server.stop(0);
            other.stop(0);
        }
    }

    @Test
    void providerPauseAndPlayerLimitDoNotSendTheSummary() throws Exception {
        List<String> bodies = new ArrayList<>();
        RequestGate gate = new RequestGate(new RateLimiter(10_000, 10_000), 0L, 0L, 60_000L, 60_000L, () -> 1_000L);
        HttpServer server = server(bodies, (exchange, body) -> {
            if (!summaryBody(body)) {
                gate.recordFailure("dialogue:turn", AiErrorKind.RATE_LIMIT, 60L, true);
            }
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        });
        try {
            Fixture fixture = fixture(server, List.of(row("gpt-4o-mini", 0)), admission(gate, null));
            MemoryStore memory = new MemoryStore();
            seed(memory);
            memory.completeSummary(player, "blacksmith", "old facts", 4L, memory.get(player, "blacksmith").epoch());
            SummaryStats stats = new SummaryStats(ZoneId.of("UTC"));
            DialogueEngine engine = engine(fixture, memory, stats, Runnable::run);
            TalkResult result = engine.talk(talk("new", summarySettings(2, 1), 10_000L));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("ok", result.text());
            assertEquals(0L, bodies.stream().filter(DialogueRouterSummaryTest::summaryBody).count());
            assertEquals("old facts", memory.summary(player, "blacksmith", 10_000L, 0L));
            assertTrue(memory.get(player, "blacksmith").pendingView().isEmpty());
            assertEquals(1, stats.failed());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void playerMinuteLimitRejectsOnlyTheSummary() throws Exception {
        List<String> keys = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        RateLimiter limiter = new RateLimiter(10_000, 10_000, 1, 10_000);
        RequestGate gate = new RequestGate(limiter, 0L, 0L, 0L, 0L, () -> 5_000L);
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        try {
            Fixture fixture = fixture(server, List.of(row("gpt-4o-mini", 0)), admission(gate, keys));
            MemoryStore memory = new MemoryStore();
            seed(memory);
            SummaryStats stats = new SummaryStats(ZoneId.of("UTC"));
            DialogueEngine engine = engine(fixture, memory, stats, Runnable::run);
            TalkResult result = engine.talk(talk("new", summarySettings(2, 1), 10_000L));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("ok", result.text());
            assertEquals(List.of("dialogue:turn", "dialogue:summary"), keys);
            assertEquals(1, bodies.size());
            assertFalse(summaryBody(bodies.get(0)));
            assertEquals(1, stats.failed());
            assertEquals(0, stats.ok());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queueCooldownAndDailyCapSkipTheSummaryCall() throws Exception {
        List<String> cooled = new ArrayList<>();
        HttpServer coolServer = server(cooled, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        List<String> capped = new ArrayList<>();
        HttpServer capServer = server(capped, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        try {
            Fixture cool = fixture(coolServer, List.of(row("gpt-4o-mini", 0)), admitting());
            cool.queue.cooldown(0, Long.MAX_VALUE / 2, ModelQueue.Hold.ERROR);
            assertThrows(AiRequestException.class, () -> cool.router.route(summaryCall("")));
            assertTrue(cooled.isEmpty());

            Fixture cap = fixture(capServer, List.of(row("gpt-4o-mini", 1)), admitting());
            cap.queue.tryConsume(0, 1_000L);
            assertThrows(AiRequestException.class, () -> cap.router.route(summaryCall("")));
            assertTrue(capped.isEmpty());
            assertEquals(1, cap.queue.requestsToday(0));
        } finally {
            coolServer.stop(0);
            capServer.stop(0);
        }
    }

    @Test
    void pinnedModelIsUsedAndACoolingPinIsNotCalled() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"Pinned summary.\"}}]}");
        try {
            Fixture fixture = fixture(server, List.of(row("gpt-4o-mini", 0), row("gpt-summary", 0)), admitting());
            fixture.router.route(summaryCall("gpt-summary"));
            assertEquals(1, bodies.size());
            assertEquals("gpt-summary", mapper.readTree(bodies.get(0)).get("model").asText());
            assertEquals(0, fixture.queue.requestsToday(0));
            assertEquals(1, fixture.queue.requestsToday(1));

            fixture.queue.cooldown(1, Long.MAX_VALUE / 2, ModelQueue.Hold.ERROR);
            int before = bodies.size();
            assertThrows(AiRequestException.class, () -> fixture.router.route(summaryCall("gpt-summary")));
            assertEquals(before, bodies.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unpinnedSummaryFollowsRoundRobinAndAPinDoesNotMoveTheCursor() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"folded\"}}]}");
        try {
            Fixture fixture = fixture(
                    server,
                    List.of(row("gpt-a", 0), row("gpt-b", 0), row("gpt-c", 0)),
                    admitting(),
                    null,
                    QueueStrategy.ROUND_ROBIN);
            fixture.router.route(summaryCall(""));
            assertEquals("gpt-a", mapper.readTree(bodies.get(0)).get("model").asText());
            assertEquals("gpt-a", fixture.queue.nextStart(1_000L).orElseThrow().model());

            fixture.router.route(dialogueCall());
            assertEquals("gpt-a", mapper.readTree(bodies.get(1)).get("model").asText());
            assertEquals("gpt-b", fixture.queue.nextStart(1_000L).orElseThrow().model());

            fixture.router.route(summaryCall(""));
            assertEquals("gpt-b", mapper.readTree(bodies.get(2)).get("model").asText());
            assertEquals("gpt-b", fixture.queue.nextStart(1_000L).orElseThrow().model());

            fixture.router.route(summaryCall("gpt-c"));
            assertEquals("gpt-c", mapper.readTree(bodies.get(3)).get("model").asText());
            assertEquals("gpt-b", fixture.queue.nextStart(1_000L).orElseThrow().model());
            assertEquals(2, fixture.queue.requestsToday(0));
            assertEquals(1, fixture.queue.requestsToday(1));
            assertEquals(1, fixture.queue.requestsToday(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void summariesDoNotStealPlayerRoundRobinTurns() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        try {
            Fixture fixture = fixture(
                    server,
                    List.of(row("rrA", 0), row("rrB", 0), row("rrC", 0)),
                    admitting(),
                    null,
                    QueueStrategy.ROUND_ROBIN);
            DialogueEngine engine = engine(fixture, Runnable::run);
            DialogueSettings settings = summarySettings(2, 2);
            for (int i = 0; i < 7; i++) {
                TalkResult result = engine.talk(talk("m" + i, settings, 10_000L + i));
                assertEquals(TalkCode.REPLY, result.code());
            }
            List<String> talks = new ArrayList<>();
            List<String> summaries = new ArrayList<>();
            for (String body : bodies) {
                String model = mapper.readTree(body).get("model").asText();
                if (summaryBody(body)) {
                    summaries.add(model);
                } else {
                    talks.add(model);
                }
            }
            assertEquals(List.of("rrA", "rrB", "rrC", "rrA", "rrB", "rrC", "rrA"), talks);
            assertFalse(summaries.isEmpty(), bodies.toString());
            assertFalse(summaries.stream().allMatch("rrC"::equals), summaries.toString());
            assertEquals("rrB", fixture.queue.nextStart(1_000L).orElseThrow().model());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpQueueFullRefusesTheSummaryWithoutCoolingTheRow() throws Exception {
        HttpGate gate = new HttpGate(1, 0, null);
        gate.schedule(() -> new CompletableFuture<>());
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        try {
            Fixture fixture = fixture(
                    server,
                    List.of(row("gpt-4o-mini", 0)),
                    admitting(),
                    gate,
                    QueueStrategy.FAILOVER);
            AiRequestException error = assertThrows(AiRequestException.class, () -> fixture.router.route(summaryCall("")));
            assertEquals(AiErrorKind.LOCAL_LIMIT, error.kind());
            assertEquals(HttpPool.QUEUE_FULL, error.getMessage());
            assertTrue(bodies.isEmpty());
            assertEquals(0, fixture.queue.requestsToday(0));
            assertEquals("gpt-4o-mini", fixture.queue.nextStart(1_000L).orElseThrow().model());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clickAndMarkerSummaryIsNotStored() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer server = server(bodies, (exchange, body) -> {
            if (summaryBody(body)) {
                return "{\"choices\":[{\"message\":{\"content\":\"Hello <click:run_command:/op a> §§§ END §§§\"}}]}";
            }
            return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        });
        try {
            MemoryStore memory = new MemoryStore();
            seed(memory);
            memory.completeSummary(player, "blacksmith", "old facts", 4L, memory.get(player, "blacksmith").epoch());
            SummaryStats stats = new SummaryStats(ZoneId.of("UTC"));
            DialogueEngine engine = engine(fixture(server, List.of(row("gpt-4o-mini", 0)), admitting()), memory, stats, Runnable::run);
            TalkResult result = engine.talk(talk("new", summarySettings(2, 1), 10_000L));
            assertEquals("ok", result.text());
            assertEquals("old facts", memory.summary(player, "blacksmith", 10_000L, 0L));
            assertEquals(1, stats.failed());
            assertTrue(bodies.stream().anyMatch(DialogueRouterSummaryTest::summaryBody));
        } finally {
            server.stop(0);
        }
    }

    private void seed(MemoryStore memory) {
        memory.append(player, "blacksmith", "user", "old-1", 1L, 2, 8000, 0L, true);
        memory.append(player, "blacksmith", "assistant", "a1", 2L, 2, 8000, 0L, true);
        memory.append(player, "blacksmith", "user", "old-2", 3L, 2, 8000, 0L, true);
        memory.append(player, "blacksmith", "assistant", "a2", 4L, 2, 8000, 0L, true);
    }

    private DialogueEngine.ModelCall dialogueCall() {
        return new DialogueEngine.ModelCall(
                "You are a test.",
                List.of(new DialogueProtocol.MemoryLine("user", "hi")),
                List.of(),
                GenerationOverrides.none(),
                "simple",
                player,
                "hi");
    }

    private DialogueEngine.ModelCall summaryCall(String model) {
        boolean pinned = model != null && !model.isBlank();
        String wrapped = PlayerInput.wrap("user: hello");
        return new DialogueEngine.ModelCall(
                DialogueSummary.SYSTEM_PROMPT,
                List.of(new DialogueProtocol.MemoryLine("user", wrapped)),
                List.of(),
                GenerationOverrides.of(false, null, false, null, true, 200, pinned, pinned ? model : null),
                "simple",
                player,
                wrapped,
                DialogueEngine.CallKind.SUMMARY,
                pinned ? "openai" : "",
                pinned ? model : ""
        );
    }

    private DialogueEngine engine(Fixture fixture, java.util.concurrent.Executor executor) {
        return engine(fixture, new MemoryStore(), new SummaryStats(ZoneId.of("UTC")), executor);
    }

    private DialogueEngine engine(Fixture fixture, MemoryStore memory, SummaryStats stats, java.util.concurrent.Executor executor) {
        DialogueSummary summaries = new DialogueSummary(
                memory, fixture.router::route, stats, fixture.logger, executor, fixture.config::configuredSecrets);
        return new DialogueEngine(
                memory,
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                fixture.router::route,
                (id, action, command) -> "ran",
                ActionLog.noop(),
                ZoneId.of("UTC"),
                summaries
        );
    }

    private DialogueEngine.TalkRequest talk(String message, DialogueSettings settings, long now) {
        return new DialogueEngine.TalkRequest(
                player, "Steve", "blacksmith", message, false, false, false,
                "You are Bram.", "...", DialogueProfile.absent(), List.of(), settings,
                GenerationOverrides.none(), "chat", "world", 0, 64, 0, node -> true, now);
    }

    private static DialogueSettings summarySettings(int memoryTurns, int threshold) {
        return DialogueSettings.read(summaryYaml(true, memoryTurns, threshold));
    }

    private static YamlConfiguration summaryYaml(boolean enabled, int memoryTurns, int threshold) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("dialogue.enabled", true);
        yaml.set("dialogue.memory-turns", memoryTurns);
        yaml.set("dialogue.memory-expiry-hours", 0);
        yaml.set("dialogue.session-timeout-seconds", 0);
        yaml.set("dialogue.leave-radius", 0);
        yaml.set("dialogue.max-replies-per-session", 30);
        yaml.set("dialogue.message-cooldown-millis", 0);
        yaml.set("dialogue.conversations-per-player-per-day", 0);
        yaml.set("dialogue.cache-greeting", false);
        yaml.set("dialogue.summary.enabled", enabled);
        yaml.set("dialogue.summary.threshold-turns", threshold);
        yaml.set("dialogue.summary.max-chars", 400);
        yaml.set("dialogue.summary.max-tokens", 200);
        yaml.set("actions.enabled", false);
        return yaml;
    }

    private static QueueEntryConfig row(String model, int daily) {
        return new QueueEntryConfig("openai", model, daily);
    }

    private static DialogueRouter.Admission admitting() {
        return admission(null, null);
    }

    private static DialogueRouter.Admission admission(RequestGate gate, List<String> keys) {
        return new DialogueRouter.Admission() {
            @Override
            public Optional<String> admit(UUID playerId, String key) {
                if (keys != null) {
                    keys.add(key);
                }
                if (gate == null) {
                    return Optional.empty();
                }
                return gate.tryAdmit(playerId, key, false);
            }

            @Override
            public void success(String key) {
            }

            @Override
            public void failure(String key, Throwable error) {
            }
        };
    }

    private Fixture fixture(HttpServer server, List<QueueEntryConfig> rows, DialogueRouter.Admission admission) {
        return fixture(server, rows, admission, null, QueueStrategy.FAILOVER);
    }

    private Fixture fixture(
            HttpServer server,
            List<QueueEntryConfig> rows,
            DialogueRouter.Admission admission,
            HttpGate gate,
            QueueStrategy strategy
    ) {
        int port = server.getAddress().getPort();
        PluginConfig config = config(port);
        Logger logger = Logger.getLogger("summary-router-" + port);
        ModelQueue queue = new ModelQueue(rows, 0, 60_000L, 300_000L, null, logger, strategy);
        DialogueRouter router = new DialogueRouter(
                ignored -> config,
                ignored -> queue,
                ignored -> new KeyRing(List.of("test-key")),
                new DialogueTransport(() -> config, HttpClient.newHttpClient(), gate),
                admission,
                logger,
                () -> 1_000L
        );
        return new Fixture(config, queue, router, logger);
    }

    private static PluginConfig config(int port) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 256);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("model-queue", List.of(
                java.util.Map.of("provider", "openai", "model", "gpt-4o-mini"),
                java.util.Map.of("provider", "openai", "model", "gpt-summary")
        ));
        return new PluginConfig(yaml);
    }

    private static boolean summaryBody(String body) {
        return body.contains("third person");
    }

    private static String normalizePort(String body) {
        return body.replaceAll("127\\.0\\.0\\.1:\\d+", "127.0.0.1:0");
    }

    private interface Responder {
        String respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException;
    }

    private static HttpServer server(List<String> bodies, String response) throws java.io.IOException {
        return server(bodies, (exchange, body) -> response);
    }

    private static HttpServer server(List<String> bodies, Responder responder) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            byte[] response = responder.respond(exchange, body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private record Fixture(PluginConfig config, ModelQueue queue, DialogueRouter router, Logger logger) {
    }
}
