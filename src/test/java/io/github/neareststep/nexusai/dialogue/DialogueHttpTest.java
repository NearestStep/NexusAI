package io.github.neareststep.nexusai.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
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
    void messageTextAloneIsNotAToolCall() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"give_iron\"}}]}";
        DialogueProtocol.ParsedCompletion parsed = DialogueProtocol.parse(body);
        assertTrue(parsed.toolNames().isEmpty());
        assertEquals("give_iron", parsed.content());
        assertTrue(DialogueProtocol.unsupportedTools(400, "tools are not supported by this model"));
        assertFalse(DialogueProtocol.unsupportedTools(500, "tools exploded"));
    }

    private DialogueEngine.ModelReply route(HttpServer server, List<CharacterAction> tools) {
        int port = server.getAddress().getPort();
        PluginConfig config = config(port);
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)),
                0,
                60_000L,
                300_000L,
                null,
                Logger.getLogger("dialogue-http"));
        DialogueRouter router = new DialogueRouter(
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
        return router.route(new DialogueEngine.ModelCall(
                "You are Bram.",
                List.of(new DialogueProtocol.MemoryLine("user", "hello")),
                tools,
                GenerationOverrides.none(),
                "simple",
                UUID.randomUUID(),
                "hello"
        ));
    }

    private static CharacterAction action() {
        return new CharacterAction("give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, 0, 1, null);
    }

    private static PluginConfig config(int port) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 0);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("model-queue", List.of(java.util.Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
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
