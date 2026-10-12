package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ChatCaller;
import io.github.neareststep.nexusai.ai.ChatExchange;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.dialogue.DialogueProtocol;
import io.github.neareststep.nexusai.dialogue.DialogueRouter;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventOrderTest {

    private EventSupport support;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (support != null) {
            support.close();
        }
        EventDispatcher.install(null);
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void successFiresPreThenPostBeforeTheCacheWrite() throws Exception {
        support = listen();
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        AiHttpClient[] holder = new AiHttpClient[1];
        AtomicBoolean cachedAtPost = new AtomicBoolean(true);
        install(event -> {
            support.events.add(event);
            if (event instanceof NexusPostGenerateEvent) {
                cachedAtPost.set(cache.get(holder[0].cacheKey("ping")).isPresent());
            }
        });
        PluginConfig config = oneRow();
        holder[0] = client(config, cache, (prompt, overrides, baseUrl, apiKey, model) ->
                new ChatExchange("pong", Map.of()));
        AiHttpClient client = holder[0];
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "harbor", "");
        assertEquals("pong", client.requestAsync("ping", null, GenerationOverrides.none(), null, null, trace).get(5, TimeUnit.SECONDS));
        assertEquals(2, support.events.size(), names());
        NexusPreGenerateEvent pre = assertInstanceOf(NexusPreGenerateEvent.class, support.events.get(0));
        NexusPostGenerateEvent post = assertInstanceOf(NexusPostGenerateEvent.class, support.events.get(1));
        assertEquals(pre.requestId(), post.requestId());
        assertEquals(RequestOrigin.PLACEHOLDER, pre.origin());
        assertEquals("nexusai", pre.consumer());
        assertEquals("pong", post.text());
        assertFalse(cachedAtPost.get());
        assertTrue(cache.get(client.cacheKey("ping")).isPresent());
    }

    @Test
    void rateLimitThenSuccessIsOnePreOneProviderErrorAndPost() throws Exception {
        support = listen();
        install(support.events::add);
        AtomicInteger calls = new AtomicInteger();
        PluginConfig config = twoRows();
        AiHttpClient client = client(config, new AiCache(Duration.ofMinutes(5), 10), (prompt, overrides, baseUrl, apiKey, model) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "slow down", null, 1L, Map.of());
            }
            return new ChatExchange("pong", Map.of());
        });
        CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "", "");
        assertEquals("pong", client.requestAsync("ping", null, GenerationOverrides.none(), null, null, trace).get(5, TimeUnit.SECONDS));
        assertEquals(3, support.events.size(), names());
        assertInstanceOf(NexusPreGenerateEvent.class, support.events.get(0));
        NexusProviderErrorEvent error = assertInstanceOf(NexusProviderErrorEvent.class, support.events.get(1));
        NexusPostGenerateEvent post = assertInstanceOf(NexusPostGenerateEvent.class, support.events.get(2));
        assertTrue(error.willRetry());
        assertEquals(NexusErrorKind.RATE_LIMIT, error.kind());
        assertEquals(429, error.httpStatus());
        assertTrue(post.attempts() > 1, "attempts=" + post.attempts());
        assertEquals(1, support.events.stream().filter(event -> event instanceof NexusPreGenerateEvent).count());
    }

    @Test
    void terminalHttpFailureFiresPreProviderErrorAndFail() throws Exception {
        support = listen();
        install(support.events::add);
        PluginConfig config = oneRow();
        AiHttpClient client = client(config, new AiCache(Duration.ofMinutes(5), 10), (prompt, overrides, baseUrl, apiKey, model) -> {
            throw new AiRequestException(AiErrorKind.OTHER, 500, "down", null);
        });
        CallTrace trace = CallTrace.start(RequestOrigin.PREWARM, null, "", "");
        assertTrue(client.requestAsync("ping", null, GenerationOverrides.none(), null, null, trace)
                .handle((text, error) -> error != null).get(5, TimeUnit.SECONDS));
        assertEquals(3, support.events.size(), names());
        assertInstanceOf(NexusPreGenerateEvent.class, support.events.get(0));
        NexusProviderErrorEvent error = assertInstanceOf(NexusProviderErrorEvent.class, support.events.get(1));
        NexusGenerateFailEvent fail = assertInstanceOf(NexusGenerateFailEvent.class, support.events.get(2));
        assertFalse(error.willRetry());
        assertEquals(NexusErrorKind.PROVIDER_ERROR, error.kind());
        assertEquals(NexusErrorKind.PROVIDER_ERROR, fail.error().kind());
    }

    @Test
    void cancelledPreSkipsHttpAndFailsCancelled() throws Exception {
        support = listen();
        AtomicInteger calls = new AtomicInteger();
        install(event -> {
            support.events.add(event);
            if (event instanceof NexusPreGenerateEvent pre) {
                pre.setCancelled(true);
                pre.setCancelReason("maintenance");
            }
        });
        PluginConfig config = oneRow();
        AiHttpClient client = client(config, new AiCache(Duration.ofMinutes(5), 10), (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange("pong", Map.of());
        });
        CallTrace trace = CallTrace.start(RequestOrigin.TEST, null, "", "");
        assertTrue(client.requestAsync("ping", null, GenerationOverrides.none(), null, null, trace)
                .handle((text, error) -> error != null).get(5, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
        assertEquals(2, support.events.size(), names());
        assertInstanceOf(NexusPreGenerateEvent.class, support.events.get(0));
        NexusGenerateFailEvent fail = assertInstanceOf(NexusGenerateFailEvent.class, support.events.get(1));
        assertEquals(NexusErrorKind.CANCELLED, fail.error().kind());
        assertTrue(fail.error().message().contains("maintenance"), fail.error().message());
    }

    @Test
    void cacheHitAndInFlightJoinDoNotFireAnotherPre() throws Exception {
        support = listen();
        install(support.events::add);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PluginConfig config = oneRow();
        AiHttpClient client = client(config, cache, (prompt, overrides, baseUrl, apiKey, model) -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new ChatExchange("pong", Map.of());
        });
        CallTrace leader = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "harbor", "");
        var first = client.requestAsync("ping", null, GenerationOverrides.none(), null, null, leader);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CallTrace joiner = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "harbor", "");
        var second = client.requestAsync("ping", null, GenerationOverrides.none(), null, null, joiner);
        release.countDown();
        assertEquals("pong", first.get(5, TimeUnit.SECONDS));
        assertEquals("pong", second.get(5, TimeUnit.SECONDS));
        assertEquals(1, support.events.stream().filter(event -> event instanceof NexusPreGenerateEvent).count());
        assertEquals(1, support.events.stream().filter(event -> event instanceof NexusPostGenerateEvent).count());

        int before = support.events.size();
        CallTrace cached = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "harbor", "");
        assertEquals("pong", client.requestAsync("ping", null, GenerationOverrides.none(), null, null, cached).get(5, TimeUnit.SECONDS));
        assertEquals(before, support.events.size());
    }

    @Test
    void admissionRefusalIsQuietForPlaceholderTalkAndPoolAndFailsTheOthers() {
        support = listen();
        install(support.events::add);
        for (RequestOrigin origin : RequestOrigin.values()) {
            support.clear();
            CallTrace trace = CallTrace.start(origin, null, "prompt", "label");
            GenerationEvents.admissionRefused(trace, NexusErrorKind.LOCAL_LIMIT, "limited");
            GenerationEvents.admissionRefused(trace, NexusErrorKind.QUOTA_EXCEEDED, "quota");
            boolean quiet = origin != RequestOrigin.API;
            if (quiet) {
                assertTrue(support.events.isEmpty(), origin + " " + names());
                continue;
            }
            assertEquals(2, support.events.size(), origin + " " + names());
            for (Event event : support.events) {
                NexusGenerateFailEvent fail = assertInstanceOf(NexusGenerateFailEvent.class, event);
                assertEquals(origin, fail.origin());
                assertTrue(fail.error().kind() == NexusErrorKind.LOCAL_LIMIT
                        || fail.error().kind() == NexusErrorKind.QUOTA_EXCEEDED);
            }
        }
    }

    @Test
    void oneTalkTurnFiresOnePreAcrossTwoModelCalls() throws Exception {
        support = listen();
        install(support.events::add);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            PluginConfig config = dialogueConfig(port);
            ModelQueue queue = new ModelQueue(
                    List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)),
                    0, 60_000L, 300_000L, null, () -> 10_000L,
                    LocalDate::now, ZoneId.of("UTC"), Logger.getLogger("event-talk"));
            DialogueRouter router = new DialogueRouter(
                    ignored -> config,
                    ignored -> queue,
                    ignored -> new io.github.neareststep.nexusai.ai.KeyRing(List.of("sk-test-0000")),
                    new DialogueTransport(() -> config, HttpClient.newHttpClient()),
                    new DialogueRouter.Admission() {
                        @Override
                        public Optional<String> admit(UUID playerId, String key) {
                            return Optional.empty();
                        }

                        @Override
                        public void success(String key) {
                        }

                        @Override
                        public void failure(String key, Throwable error) {
                        }
                    },
                    Logger.getLogger("event-talk"),
                    () -> 10_000L);
            UUID player = UUID.randomUUID();
            CallTrace trace = CallTrace.start(RequestOrigin.TALK, player, "blacksmith", "");
            DialogueEngineCall call = new DialogueEngineCall(player, trace);
            router.route(call.first());
            router.route(call.first());
            assertEquals(2, hits.get());
            assertEquals(1, support.events.stream().filter(event -> event instanceof NexusPreGenerateEvent).count(), names());
            GenerationEvents.postText(trace, "hello");
            NexusPostGenerateEvent post = support.events.stream()
                    .filter(event -> event instanceof NexusPostGenerateEvent)
                    .map(event -> (NexusPostGenerateEvent) event)
                    .findFirst()
                    .orElseThrow();
            assertEquals(trace.requestId(), post.requestId());
            assertEquals("hello", post.text());
            assertTrue(post.attempts() >= 2, "attempts=" + post.attempts());
        } finally {
            server.stop(0);
        }
    }

    private void install(java.util.function.Consumer<Event> caller) {
        Logger logger = Logger.getLogger("event-order-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        EventDispatcher.install(EventDispatcher.create(logger, List::of, caller, System::nanoTime, () -> false));
    }

    private EventSupport listen() {
        return EventSupport.register("Order");
    }

    private String names() {
        return support.events.stream().map(event -> event.getClass().getSimpleName()).toList().toString();
    }

    private AiHttpClient client(PluginConfig config, AiCache cache, ChatCaller http) {
        executor = Executors.newSingleThreadExecutor();
        Logger logger = Logger.getLogger("event-order-http-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        ModelQueue queue = new ModelQueue(
                config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                LocalDate::now, ZoneId.of("UTC"), logger);
        RoutingProvider provider = new RoutingProvider(config, queue, http, executor, logger);
        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), config::configuredSecrets);
        return new AiHttpClient(cache, provider, config, RequestGate.permissive(), diagnostics, logger);
    }

    private static PluginConfig oneRow() {
        YamlConfiguration yaml = baseYaml();
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        yaml.set("providers.openai.api-key", "sk-one-1111");
        return new PluginConfig(yaml);
    }

    private static PluginConfig twoRows() {
        YamlConfiguration yaml = baseYaml();
        yaml.createSection("providers.groq");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://example.invalid/v1");
        yaml.set("providers.groq.api-key", "sk-two-2222");
        yaml.set("providers.openai.api-key", "sk-one-1111");
        yaml.set("model-queue", List.of(
                Map.of("provider", "openai", "model", "gpt-4o-mini"),
                Map.of("provider", "groq", "model", "llama")));
        return new PluginConfig(yaml);
    }

    private static PluginConfig dialogueConfig(int port) {
        YamlConfiguration yaml = baseYaml();
        yaml.set("providers.openai.url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("providers.openai.api-key", "sk-test-0000");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static YamlConfiguration baseYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "sk-one-1111");
        yaml.set("fallback", "...");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://example.invalid/v1");
        return yaml;
    }

    /** Builds the dialogue call the router expects without pulling the engine into this test. */
    private record DialogueEngineCall(UUID player, CallTrace trace) {
        io.github.neareststep.nexusai.dialogue.DialogueEngine.ModelCall first() {
            return new io.github.neareststep.nexusai.dialogue.DialogueEngine.ModelCall(
                    "You are Bram.",
                    List.of(new DialogueProtocol.MemoryLine("user", "hi")),
                    List.of(),
                    GenerationOverrides.none(),
                    "chat",
                    player,
                    "hi",
                    trace);
        }
    }
}
