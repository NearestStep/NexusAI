package io.github.neareststep.nexusai.ai;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.dialogue.ActionGate;
import io.github.neareststep.nexusai.dialogue.ActionLog;
import io.github.neareststep.nexusai.dialogue.CharacterAction;
import io.github.neareststep.nexusai.dialogue.DialogueBudget;
import io.github.neareststep.nexusai.dialogue.DialogueEngine;
import io.github.neareststep.nexusai.dialogue.DialogueProfile;
import io.github.neareststep.nexusai.dialogue.DialogueProtocol;
import io.github.neareststep.nexusai.dialogue.DialogueRouter;
import io.github.neareststep.nexusai.dialogue.DialogueSettings;
import io.github.neareststep.nexusai.dialogue.DialogueSummary;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import io.github.neareststep.nexusai.dialogue.GreetingCache;
import io.github.neareststep.nexusai.dialogue.MemoryStore;
import io.github.neareststep.nexusai.dialogue.SessionBook;
import io.github.neareststep.nexusai.dialogue.TurnMemory;
import io.github.neareststep.nexusai.moderation.ModerationService;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.prewarm.PrewarmService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallTraceTest {

    @Test
    void chatEntrancesReachTheCaller() throws Exception {
        RecordingCaller http = new RecordingCaller();
        PluginConfig config = chatConfig();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 50);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ModelQueue queue = new ModelQueue(
                    config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                    () -> LocalDate.of(2026, 1, 1), ZoneId.of("UTC"), Logger.getLogger("trace-queue"));
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("trace-route"), () -> 10_000L);
            AiHttpClient client = new AiHttpClient(cache, provider, config, Logger.getLogger("trace-http"));

            PrewarmService prewarm = new PrewarmService(
                    prewarmConfig(), cache, client, scheduler, Logger.getLogger("trace-prewarm"));
            prewarm.start();
            awaitOrigin(http, RequestOrigin.PREWARM);
            CallTrace prewarmTrace = last(http, RequestOrigin.PREWARM);
            assertEquals("hello", prewarmTrace.promptId());
            assertNull(prewarmTrace.playerId());
            assertEquals("nexusai", prewarmTrace.consumer());
            prewarm.shutdown();

            PoolService pool = new PoolService(poolConfig(), new AiPool(), client, Logger.getLogger("trace-pool"));
            pool.start();
            awaitOrigin(http, RequestOrigin.POOL);
            assertEquals("tip", last(http, RequestOrigin.POOL).promptId());
            pool.shutdown();

            assertEquals("ok", client.testAsync("probe").join());
            CallTrace test = last(http, RequestOrigin.TEST);
            assertEquals("", test.promptId());
            assertNull(test.playerId());

            UUID player = UUID.randomUUID();
            CallTrace placeholder = CallTrace.start(RequestOrigin.PLACEHOLDER, player, "rules", "");
            assertEquals("ok", client.requestAsync("rules text", player, GenerationOverrides.none(), null, "", placeholder).join());
            CallTrace forwarded = last(http, RequestOrigin.PLACEHOLDER);
            assertEquals(placeholder.requestId(), forwarded.requestId());
            assertEquals(player, forwarded.playerId());
            assertEquals("rules", forwarded.promptId());

            CallTrace explicitTest = CallTrace.start(RequestOrigin.TEST, player, "rules", "");
            assertEquals("ok", client.testAsync("named", GenerationOverrides.none(), explicitTest).join());
            assertEquals(explicitTest.requestId(), last(http, RequestOrigin.TEST).requestId());
        } finally {
            scheduler.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    void talkGreetingAndSummaryCarryTheirOrigin() {
        List<CallTrace> seen = new ArrayList<>();
        DialogueEngine engine = engine(call -> {
            seen.add(call.trace());
            if (!call.tools().isEmpty()) {
                return new DialogueEngine.ModelReply("", List.of("give_iron"), false);
            }
            return DialogueEngine.ModelReply.text("done");
        });
        UUID player = UUID.randomUUID();
        engine.talk(talk(player, "", "blacksmith", new DialogueProfile(true, null, null, null, null, null, null), List.of()));
        assertEquals(RequestOrigin.TALK_GREETING, seen.getFirst().origin());
        assertEquals("blacksmith", seen.getFirst().promptId());
        assertEquals(player, seen.getFirst().playerId());

        seen.clear();
        engine.talk(talk(player, "", "guide", new DialogueProfile(true, "Hello.", null, null, null, null, null), List.of()));
        assertTrue(seen.isEmpty());

        seen.clear();
        CharacterAction action = new CharacterAction("give_iron", "Give", "give iron", true, 0, 0, null);
        engine.talk(talk(player, "please", "blacksmith", DialogueProfile.absent(), List.of(action)));
        assertEquals(2, seen.size());
        assertEquals(RequestOrigin.TALK, seen.get(0).origin());
        assertEquals(seen.get(0).requestId(), seen.get(1).requestId());
        assertEquals("blacksmith", seen.get(0).promptId());

        seen.clear();
        DialogueSummary summary = new DialogueSummary(
                new MemoryStore(),
                call -> {
                    seen.add(call.trace());
                    return DialogueEngine.ModelReply.text("They talked.");
                },
                null,
                Logger.getLogger("trace-summary"),
                Runnable::run);
        summary.schedule(new DialogueSummary.SummaryJob(
                player, "blacksmith", "", List.of(new TurnMemory.Line("user", "hi")), 1, DialogueSettings.defaults(), 1L));
        assertEquals(1, seen.size());
        assertEquals(RequestOrigin.SUMMARY, seen.getFirst().origin());
        assertEquals("blacksmith", seen.getFirst().promptId());
        assertEquals(player, seen.getFirst().playerId());
        assertEquals("nexusai", seen.getFirst().consumer());
    }

    @Test
    void dialogueRouterDeliversTheSameRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        List<CallTrace> delivered = new CopyOnWriteArrayList<>();
        CallTrace.capture(delivered::add);
        try {
            PluginConfig config = dialogueConfig(server.getAddress().getPort());
            DialogueRouter router = router(config);
            AtomicReference<CallTrace> sent = new AtomicReference<>();
            DialogueEngine engine = engine(call -> {
                sent.set(call.trace());
                return router.route(call);
            });
            UUID player = UUID.randomUUID();
            engine.talk(talk(player, "hello", "blacksmith", DialogueProfile.absent(), List.of()));
            assertEquals(1, delivered.size());
            assertEquals(sent.get().requestId(), delivered.getFirst().requestId());
            assertEquals(RequestOrigin.TALK, delivered.getFirst().origin());
            assertEquals("blacksmith", delivered.getFirst().promptId());
        } finally {
            CallTrace.endCapture();
            server.stop(0);
        }
    }

    @Test
    void moderationDeliversOnlyWhenItCalls() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"flagged\\\":false,\\\"category\\\":\\\"none\\\",\\\"reason\\\":\\\"\\\"}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<CallTrace> delivered = new CopyOnWriteArrayList<>();
        CallTrace.capture(delivered::add);
        try {
            int port = server.getAddress().getPort();
            PluginConfig config = moderationConfig(port);
            ModelQueue queue = new ModelQueue(
                    config.modelQueue(), 0, 60_000L, 300_000L, null, Logger.getLogger("trace-mod-queue"));
            OpenAiProvider http = new OpenAiProvider(config, executor, Logger.getLogger("trace-mod-http"));
            ModerationService service = new ModerationService(
                    config.moderation(), config, queue, http, null, null, null, Logger.getLogger("trace-mod"));
            UUID player = UUID.randomUUID();
            assertEquals(ModerationService.Skip.TOO_SHORT, service.check(player, "Steve", "hi", false).skip());
            assertTrue(delivered.isEmpty());
            assertEquals(ModerationService.Skip.CHECKED, service.check(player, "Steve", "hello there friend", false).skip());
            assertEquals(1, delivered.size());
            assertEquals(RequestOrigin.MODERATION, delivered.getFirst().origin());
            assertEquals(player, delivered.getFirst().playerId());
            assertEquals("", delivered.getFirst().promptId());
            assertEquals("nexusai", delivered.getFirst().consumer());
        } finally {
            CallTrace.endCapture();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static CallTrace last(RecordingCaller http, RequestOrigin origin) {
        CallTrace found = null;
        for (CallTrace trace : http.traces) {
            if (trace != null && trace.origin() == origin) {
                found = trace;
            }
        }
        assertTrue(found != null, origin.name());
        return found;
    }

    private static void awaitOrigin(RecordingCaller http, RequestOrigin origin) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            for (CallTrace trace : http.traces) {
                if (trace != null && trace.origin() == origin) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("missing " + origin);
    }

    private static DialogueEngine engine(DialogueEngine.DialogueModel model) {
        return new DialogueEngine(
                new MemoryStore(),
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                model,
                (id, action, command) -> "ran",
                ActionLog.noop(),
                ZoneId.of("UTC"));
    }

    private static DialogueEngine.TalkRequest talk(
            UUID player,
            String message,
            String character,
            DialogueProfile profile,
            List<CharacterAction> actions
    ) {
        return new DialogueEngine.TalkRequest(
                player, "Steve", character, message, false, false, false, "You are Bram.", "...",
                profile, actions, DialogueSettings.defaults(), GenerationOverrides.none(), "chat",
                "world", 0, 64, 0, node -> true, 1_000L);
    }

    private static DialogueRouter router(PluginConfig config) {
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)),
                0, 60_000L, 300_000L, null, Logger.getLogger("trace-dialogue"));
        return new DialogueRouter(
                ignored -> config,
                ignored -> queue,
                ignored -> new KeyRing(List.of("test-key")),
                new DialogueTransport(() -> config, java.net.http.HttpClient.newHttpClient()),
                new DialogueRouter.Admission() {
                    @Override
                    public java.util.Optional<String> admit(UUID playerId, String key) {
                        return java.util.Optional.empty();
                    }

                    @Override
                    public void success(String key) {
                    }

                    @Override
                    public void failure(String key, Throwable error) {
                    }

                    @Override
                    public boolean pauseIsGlobal() {
                        return false;
                    }
                },
                Logger.getLogger("trace-router"),
                () -> 10_000L);
    }

    private static PluginConfig chatConfig() {
        YamlConfiguration yaml = base("https://api.openai.com/v1");
        yaml.set("limits.requests-per-minute", 100);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("providers.openai.api-key", "sk-test");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static PluginConfig prewarmConfig() {
        YamlConfiguration yaml = base("https://api.openai.com/v1");
        yaml.set("api.key", "sk-test");
        yaml.set("limits.requests-per-minute", 100);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("prewarm.enabled", true);
        yaml.set("prewarm.prompts", List.of("hello"));
        return new PluginConfig(yaml);
    }

    private static PluginConfig poolConfig() {
        YamlConfiguration yaml = base("https://api.openai.com/v1");
        yaml.set("api.key", "sk-test");
        yaml.set("limits.requests-per-minute", 100);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("pool.enabled", true);
        yaml.set("pool.entries", List.of(Map.of("prompt", "tip", "size", 1, "min-threshold", 0)));
        return new PluginConfig(yaml);
    }

    private static PluginConfig dialogueConfig(int port) {
        String url = "http://127.0.0.1:" + port + "/v1";
        YamlConfiguration yaml = base(url);
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static PluginConfig moderationConfig(int port) {
        String url = "http://127.0.0.1:" + port + "/v1";
        YamlConfiguration yaml = base(url);
        yaml.set("providers.openai.api-key", List.of("test-key"));
        yaml.set("moderation.enabled", true);
        yaml.set("moderation.min-length", 8);
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static YamlConfiguration base(String url) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", url);
        yaml.set("api.key", "");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", url);
        return yaml;
    }

    private static final class RecordingCaller implements ChatCaller {
        private final List<CallTrace> traces = new CopyOnWriteArrayList<>();

        @Override
        public ChatExchange exchange(
                String prompt,
                GenerationOverrides overrides,
                String baseUrl,
                String apiKey,
                String model
        ) {
            return new ChatExchange("ok", Map.of());
        }

        @Override
        public java.util.concurrent.CompletableFuture<ChatExchange> exchangeAsync(
                String prompt,
                GenerationOverrides overrides,
                String baseUrl,
                String apiKey,
                String model,
                CallTrace trace
        ) {
            traces.add(trace);
            return java.util.concurrent.CompletableFuture.completedFuture(exchange(prompt, overrides, baseUrl, apiKey, model));
        }
    }
}
