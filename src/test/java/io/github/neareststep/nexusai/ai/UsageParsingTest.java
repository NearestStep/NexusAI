package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.dto.UsageJson;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.dialogue.DialogueProtocol;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void characterEstimateRoundsUpAndCountsCodePoints() {
        assertEquals(0, ResponseUsage.tokensFromChars(0));
        assertEquals(1, ResponseUsage.tokensFromChars(1));
        assertEquals(1, ResponseUsage.tokensFromChars(4));
        assertEquals(2, ResponseUsage.tokensFromChars(5));
        assertEquals(1, ResponseUsage.chars("я"));
        assertEquals(1, ResponseUsage.chars("\uD83D\uDE00"));
        ResponseUsage estimate = ResponseUsage.estimate(5, 1);
        assertEquals(2, estimate.promptTokens());
        assertEquals(1, estimate.completionTokens());
        assertEquals(3, estimate.totalTokens());
        assertTrue(estimate.estimated());
        assertFalse(estimate.reported());
    }

    @Test
    void errorBodyUsageIsReportedAndABodyWithoutUsageIsEmpty() {
        ResponseUsage usage = UsageJson.fromDocument(
                "{\"error\":{\"message\":\"no\"},\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":1,\"total_tokens\":5}}");
        assertTrue(usage.reported());
        assertEquals(4, usage.promptTokens());
        assertEquals(1, usage.completionTokens());
        assertEquals(5, usage.totalTokens());
        ResponseUsage html = UsageJson.fromDocument("<html>no</html>");
        assertFalse(html.reported());
        assertFalse(html.estimated());
        ResponseUsage missing = UsageJson.fromDocument("{\"error\":{\"message\":\"no\"}}");
        assertFalse(missing.reported());
        assertFalse(missing.estimated());
        assertEquals(0, missing.totalTokens());
    }

    @Test
    void openAiProviderKeepsReportedUsage() throws Exception {
        ChatExchange exchange = exchange(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"pong.\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"cost\":1.5}}");
        // A length cutoff keeps a sentence that ends past the halfway point.
        assertEquals("pong.", exchange.text());
        assertEquals("", exchange.providerId());
        assertEquals("gpt-4o-mini", exchange.model());
        assertEquals("length", exchange.finishReason());
        assertTrue(exchange.httpNanos() > 0L);
        assertTrue(exchange.usage().reported());
        assertFalse(exchange.usage().estimated());
        assertEquals(100, exchange.usage().promptTokens());
        assertEquals(20, exchange.usage().completionTokens());
        assertEquals(120, exchange.usage().totalTokens());
        assertEquals(1.5d, exchange.usage().cost().orElseThrow());
    }

    @Test
    void openAiProviderEstimatesWhenUsageIsMissing() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        ChatExchange exchange = exchange(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}]}",
                request);
        int promptChars = 0;
        for (JsonNode message : mapper.readTree(request.get()).path("messages")) {
            promptChars += ResponseUsage.chars(message.path("content").asText(""));
        }
        ResponseUsage expected = ResponseUsage.estimate(promptChars, ResponseUsage.chars("pong"));
        assertEquals(expected, exchange.usage());
        assertTrue(exchange.usage().estimated());
        assertFalse(mapper.readTree(request.get()).has("usage"));
    }

    @Test
    void emptyContentKeepsReportedUsageAndDoesNotEstimate() throws Exception {
        AiRequestException reported = failure(
                "{\"choices\":[{\"message\":{\"content\":\"\"}}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":1,\"total_tokens\":5}}");
        assertTrue(reported.usage().reported());
        assertEquals(5, reported.usage().totalTokens());
        AiRequestException missing = failure("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}");
        assertEquals(ResponseUsage.none(), missing.usage());
    }

    @Test
    void dialogueProtocolParsesTheSameUsageShapes() throws Exception {
        DialogueProtocol.ParsedCompletion full = DialogueProtocol.parse(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}],"
                        + "\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":2,\"total_tokens\":3,\"extra\":true}}");
        assertEquals("pong", full.content());
        assertEquals("stop", full.finishReason());
        assertEquals(10, full.usage().totalTokens());
        assertTrue(full.usage().reported());
        DialogueProtocol.ParsedCompletion absent = DialogueProtocol.parse(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}]}");
        assertEquals(ResponseUsage.none(), absent.usage());
        DialogueProtocol.ParsedCompletion partial = DialogueProtocol.parse(
                "{\"choices\":[{\"message\":{\"content\":\"pong\"}}],\"usage\":{\"total_tokens\":12}}");
        assertEquals(12, partial.usage().totalTokens());
        assertEquals(0, partial.usage().promptTokens());
    }

    @Test
    void dialoguePromptCharsMatchTheRequestBody() throws Exception {
        String system = "You are Bram.";
        List<DialogueProtocol.MemoryLine> messages = List.of(new DialogueProtocol.MemoryLine("user", "hello"));
        byte[] json = DialogueProtocol.requestJson("gpt-4o-mini", system, messages, List.of(), null, null, null, null);
        int fromJson = 0;
        for (JsonNode message : mapper.readTree(json).path("messages")) {
            fromJson += ResponseUsage.chars(message.path("content").asText(""));
        }
        assertEquals(fromJson, DialogueProtocol.promptChars(system, messages));
        assertFalse(new String(json, StandardCharsets.UTF_8).contains("\"usage\""));
    }

    @Test
    void dialogueTransportEstimatesAndKeepsReportedUsage() throws Exception {
        DialogueTransport.Result missing = send(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}]}");
        int prompt = DialogueProtocol.promptChars("system", List.of(new DialogueProtocol.MemoryLine("user", "hi")));
        assertEquals(ResponseUsage.estimate(prompt, ResponseUsage.chars("pong")), missing.usage());
        assertTrue(missing.httpNanos() > 0L);
        DialogueTransport.Result reported = send(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"pong\"}}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}");
        assertTrue(reported.usage().reported());
        assertEquals(4, reported.usage().totalTokens());
        assertFalse(reported.usage().estimated());
    }

    private ChatExchange exchange(String responseBody) throws Exception {
        return exchange(responseBody, new AtomicReference<>());
    }

    private ChatExchange exchange(String responseBody, AtomicReference<String> request) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", http -> {
            request.set(new String(http.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            http.sendResponseHeaders(200, response.length);
            http.getResponseBody().write(response);
            http.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PluginConfig current = config(server.getAddress().getPort());
            OpenAiProvider provider = new OpenAiProvider(current, executor, java.util.logging.Logger.getLogger("usage"));
            return provider.exchange(
                    "hello",
                    io.github.neareststep.nexusai.config.GenerationOverrides.none(),
                    current.getBaseUrl(),
                    "test-key",
                    "gpt-4o-mini");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private AiRequestException failure(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", http -> {
            http.getRequestBody().readAllBytes();
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            http.sendResponseHeaders(200, response.length);
            http.getResponseBody().write(response);
            http.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PluginConfig current = config(server.getAddress().getPort());
            OpenAiProvider provider = new OpenAiProvider(current, executor, java.util.logging.Logger.getLogger("usage-empty"));
            return assertThrows(AiRequestException.class, () -> provider.exchange(
                    "hello",
                    io.github.neareststep.nexusai.config.GenerationOverrides.none(),
                    current.getBaseUrl(),
                    "test-key",
                    "gpt-4o-mini"));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private DialogueTransport.Result send(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", http -> {
            http.getRequestBody().readAllBytes();
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            http.sendResponseHeaders(200, response.length);
            http.getResponseBody().write(response);
            http.close();
        });
        server.start();
        try {
            PluginConfig config = config(server.getAddress().getPort());
            DialogueTransport transport = new DialogueTransport(() -> config, HttpClient.newHttpClient());
            return transport.send(new DialogueTransport.Request(
                    config.getBaseUrl(),
                    "test-key",
                    "gpt-4o-mini",
                    "system",
                    List.of(new DialogueProtocol.MemoryLine("user", "hi")),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    config.getReadTimeout()));
        } finally {
            server.stop(0);
        }
    }

    private static PluginConfig config(int port) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.system-prompt", "");
        yaml.set("api.temperature", -1);
        yaml.set("api.max-tokens", 0);
        yaml.set("api.strip-markdown", false);
        return new PluginConfig(yaml);
    }
}
