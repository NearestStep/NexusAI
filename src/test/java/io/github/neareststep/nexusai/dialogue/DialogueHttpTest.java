package io.github.neareststep.nexusai.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueHttpTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mockServerReceivesToolsAndOnlyTheFunctionNameIsKept() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server((exchange, attempt) -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"Here.",
                    "tool_calls":[{"id":"call_1","type":"function","function":{"name":"give_iron","arguments":"{\\"item\\":\\"diamond_block\\"}"}}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            DialogueEngine.ModelReply reply = route(server, List.of(action()));
            JsonNode json = mapper.readTree(body.get());
            assertTrue(json.has("tools"));
            assertEquals("give_iron", json.get("tools").get(0).get("function").get("name").asText());
            assertEquals("auto", json.get("tool_choice").asText());
            assertFalse(json.get("tools").get(0).get("function").get("parameters").get("properties").fieldNames().hasNext());
            assertTrue(json.get("messages").get(0).get("content").asText().contains("PLAYER INPUT")
                    || json.get("messages").get(0).get("content").asText().contains("player wrote"));
            assertEquals(List.of("give_iron"), reply.toolNames());
            assertFalse(reply.toolsUnsupported());
            assertFalse(reply.text() != null && reply.text().contains("diamond_block"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsupportedToolsRetryOmitsToolsAndDoesNotReadTheActionName() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> secondBody = new AtomicReference<>();
        HttpServer server = server((exchange, attempt) -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n = attempts.incrementAndGet();
            if (n == 1) {
                byte[] response = "{\"error\":{\"message\":\"Unrecognized request argument supplied: tools\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            secondBody.set(payload);
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"give_iron\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            DialogueEngine.ModelReply reply = route(server, List.of(action()));
            assertEquals(2, attempts.get());
            JsonNode second = mapper.readTree(secondBody.get());
            assertFalse(second.has("tools"));
            assertTrue(reply.toolsUnsupported());
            assertTrue(reply.toolNames().isEmpty());
            assertEquals("give_iron", reply.text());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void talkSendsTheDefaultCapAndAHigherPromptOverride() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server((exchange, attempt) -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"Hello."}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            route(server, List.of(), GenerationOverrides.none(), PluginConfig.DEFAULT_MAX_TOKENS);
            JsonNode defaults = mapper.readTree(body.get());
            assertEquals(PluginConfig.DEFAULT_MAX_TOKENS, defaults.get("max_tokens").asInt());
            assertFalse(defaults.has("max_completion_tokens"));

            route(server, List.of(), GenerationOverrides.of(false, null, false, null, true, 768), PluginConfig.DEFAULT_MAX_TOKENS);
            JsonNode higher = mapper.readTree(body.get());
            assertEquals(768, higher.get("max_tokens").asInt());

            route(server, List.of(), GenerationOverrides.of(false, null, false, null, true, -1), PluginConfig.DEFAULT_MAX_TOKENS);
            JsonNode omitted = mapper.readTree(body.get());
            assertFalse(omitted.has("max_tokens"));
            assertFalse(omitted.has("max_completion_tokens"));

            route(server, List.of(), GenerationOverrides.none(), 0);
            JsonNode zero = mapper.readTree(body.get());
            assertFalse(zero.has("max_tokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void messageTextAloneIsNotAToolCall() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"give_iron\"}}]}";
        DialogueProtocol.ParsedCompletion parsed = DialogueProtocol.parse(body);
        assertTrue(parsed.toolNames().isEmpty());
        assertEquals("give_iron", parsed.content());
        assertTrue(DialogueProtocol.unsupportedTools(400, "tools are not supported by this model"));
        assertFalse(DialogueProtocol.unsupportedTools(500, "tools exploded"));
    }

    private DialogueEngine.ModelReply route(HttpServer server, List<CharacterAction> tools) {
        return route(server, tools, GenerationOverrides.none(), 0);
    }

    private DialogueEngine.ModelReply route(
            HttpServer server,
            List<CharacterAction> tools,
            GenerationOverrides overrides,
            int maxTokens
    ) {
        int port = server.getAddress().getPort();
        PluginConfig config = config(port, maxTokens);
        DialogueRouter router = router(config);
        String wrapped = io.github.neareststep.nexusai.ai.PlayerInput.wrap("hello");
        return router.route(new DialogueEngine.ModelCall(
                "You are Bram.",
                List.of(new DialogueProtocol.MemoryLine("user", wrapped)),
                tools,
                overrides,
                "simple",
                UUID.randomUUID(),
                wrapped
        ));
    }

    @Test
    void lengthCutTalkReplyIsTrimmedAndRemembered() throws Exception {
        String raw = "The harbor is quiet today. Ships wait at the dock. Then the tide suddenly cu";
        String trimmed = "The harbor is quiet today. Ships wait at the dock.";
        AtomicReference<String> secondBody = new AtomicReference<>();
        HttpServer server = server((exchange, attempt) -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response;
            if (attempt == 1) {
                response = ("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":"
                        + mapper.writeValueAsString(raw) + "}}]}").getBytes(StandardCharsets.UTF_8);
            } else {
                secondBody.set(payload);
                response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Sure.\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            PluginConfig config = config(server.getAddress().getPort(), PluginConfig.DEFAULT_MAX_TOKENS);
            DialogueRouter router = router(config);
            DialogueEngine engine = new DialogueEngine(
                    new MemoryStore(),
                    new SessionBook(),
                    new ActionGate(),
                    new DialogueBudget(),
                    new GreetingCache(),
                    router::route,
                    (id, action, command) -> "ran",
                    ActionLog.noop(),
                    ZoneId.of("UTC")
            );
            UUID player = UUID.randomUUID();
            TalkResult first = engine.talk(talk(player, "hello", 1_000L));
            assertEquals(trimmed, first.text());
            assertFalse(first.text().contains("suddenly"));
            TalkResult second = engine.talk(talk(player, "again", 2_000L));
            assertEquals("Sure.", second.text());
            assertTrue(secondBody.get().contains(trimmed));
            assertFalse(secondBody.get().contains("suddenly cu"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lengthCutDialogueReplyTrimsOnAWordAndStillStripsColourCodes() {
        DialogueTransport transport = new DialogueTransport(() -> config(1), HttpClient.newHttpClient());
        String reply = transport.finishText(
                "Hello &cworld. The traveler walked toward the §cmount", "hello", "simple", "", "length");
        assertEquals("Hello world. The traveler walked toward the…", reply);
        assertFalse(reply.contains("&"));
        assertFalse(reply.contains("§"));
        assertEquals("The harbor is qui", transport.finishText("The harbor is qui", "hello", "simple", "", "stop"));
        AiRequestException error = assertThrows(
                AiRequestException.class,
                () -> transport.finishText("&c§l", "hello", "simple", "", "length"));
        assertEquals(AiErrorKind.EMPTY_REPLY, error.kind());
        assertEquals(PlayerInput.EMPTY_REPLY, error.getMessage());
    }

    @Test
    void dialogueReplyStripsSectionSignsBeforeItIsShown() {
        DialogueTransport transport = new DialogueTransport(() -> config(1), HttpClient.newHttpClient());
        String reply = transport.finishText(
                "In the §plains§ biome §cnow &cAMPRED &x&f&f&0&0&0&0HEX", "hello", "simple", "");
        assertEquals("In the plains biome now AMPRED HEX", reply);
        assertFalse(reply.contains("§"));
        assertFalse(reply.contains("&c"));
        assertFalse(reply.contains("&x"));
    }

    @Test
    void colourOnlyDialogueReplyNamesTheEmptyReply() {
        DialogueTransport transport = new DialogueTransport(() -> config(1), HttpClient.newHttpClient());
        AiRequestException error = assertThrows(
                AiRequestException.class,
                () -> transport.finishText("&c§l", "hello", "simple", ""));
        assertEquals(AiErrorKind.EMPTY_REPLY, error.kind());
        assertEquals(PlayerInput.EMPTY_REPLY, error.getMessage());
    }

    private static CharacterAction action() {
        return new CharacterAction("give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, 0, 1, null);
    }

    private static PluginConfig config(int port) {
        return config(port, 0);
    }

    private static PluginConfig config(int port, int maxTokens) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", maxTokens);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("model-queue", List.of(java.util.Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static DialogueRouter router(PluginConfig config) {
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)),
                0,
                60_000L,
                300_000L,
                null,
                Logger.getLogger("dialogue-http"));
        return new DialogueRouter(
                ignored -> config,
                ignored -> queue,
                ignored -> new KeyRing(List.of("test-key")),
                new DialogueTransport(() -> config, java.net.http.HttpClient.newHttpClient()),
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
                Logger.getLogger("dialogue-http"),
                () -> 1_000L
        );
    }

    private static DialogueEngine.TalkRequest talk(UUID player, String message, long now) {
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
                now
        );
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, int attempt) throws java.io.IOException;
    }

    private static HttpServer server(Handler handler) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange, attempts.incrementAndGet()));
        server.start();
        return server;
    }
}
