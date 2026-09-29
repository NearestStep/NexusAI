package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omittedGenerationSettingsStayOutOfTheBody() throws Exception {
        PluginConfig config = config("gpt-4o-mini", "", -1, 0, "", false, 0, 0, "low");
        JsonNode json = mapper.valueToTree(OpenAiProvider.buildBody(config, "hi", GenerationOverrides.none()));
        assertEquals("gpt-4o-mini", json.get("model").asText());
        assertEquals(2, json.get("messages").size());
        assertEquals("system", json.get("messages").get(0).get("role").asText());
        assertEquals(PlayerInput.GUARD, json.get("messages").get(0).get("content").asText());
        assertEquals("user", json.get("messages").get(1).get("role").asText());
        assertEquals("hi", json.get("messages").get(1).get("content").asText());
        assertFalse(json.has("temperature"));
        assertFalse(json.has("max_tokens"));
        assertFalse(json.has("max_completion_tokens"));
        assertFalse(json.has("reasoning_effort"));
    }

    @Test
    void modelOverrideReplacesTheGlobalModel() {
        PluginConfig config = config("gpt-4o-mini", "", -1, 0, "", false, 0, 0, "low");
        GenerationOverrides overrides = GenerationOverrides.of(false, null, false, null, false, null, true, "custom-model");
        JsonNode json = mapper.valueToTree(OpenAiProvider.buildBody(config, "hi", overrides));
        assertEquals("custom-model", json.get("model").asText());
    }

    @Test
    void globalAndPoolOverridesShapeTheBody() throws Exception {
        PluginConfig config = config("gpt-4o-mini", "Be brief.", 0.7, 256, "low", false, 0, 0, "low");
        GenerationOverrides overrides = GenerationOverrides.of(true, "Pool system", true, 0.0, true, 32);
        JsonNode json = mapper.valueToTree(OpenAiProvider.buildBody(config, "hi", overrides));
        assertEquals("system", json.get("messages").get(0).get("role").asText());
        assertTrue(json.get("messages").get(0).get("content").asText().startsWith("Pool system"));
        assertTrue(json.get("messages").get(0).get("content").asText().endsWith(PlayerInput.GUARD));
        assertEquals("user", json.get("messages").get(1).get("role").asText());
        assertEquals(0.0, json.get("temperature").asDouble());
        assertEquals(32, json.get("max_tokens").asInt());
        assertFalse(json.has("reasoning_effort"));
    }

    @Test
    void reasoningModelsGetEffortAndATokenFloor() throws Exception {
        PluginConfig oss = config("openai/gpt-oss-20b", "", 0.4, 100, "low", false, 0, 0, "low");
        JsonNode ossJson = mapper.valueToTree(OpenAiProvider.buildBody(oss, "hi", GenerationOverrides.none()));
        assertEquals("low", ossJson.get("reasoning_effort").asText());
        assertTrue(ossJson.get("max_tokens").asInt() >= ReasoningModels.TOKEN_FLOOR);
        assertFalse(ossJson.has("max_completion_tokens"));
        assertEquals(0.4, ossJson.get("temperature").asDouble());

        PluginConfig oSeries = config("o3-mini", "system", 0.4, 100, "low", false, 0, 0, "low");
        JsonNode oJson = mapper.valueToTree(OpenAiProvider.buildBody(oSeries, "hi", GenerationOverrides.none()));
        assertEquals("low", oJson.get("reasoning_effort").asText());
        assertTrue(oJson.get("max_completion_tokens").asInt() >= ReasoningModels.TOKEN_FLOOR);
        assertFalse(oJson.has("max_tokens"));
        assertFalse(oJson.has("temperature"));
        assertTrue(oJson.get("messages").get(0).get("content").asText().startsWith("system"));
        assertTrue(oJson.get("messages").get(0).get("content").asText().endsWith(PlayerInput.GUARD));
    }

    @Test
    void blankReasoningEffortIsNotSent() {
        PluginConfig config = config("o1", "", -1, 0, "", false, 0, 0, "off");
        JsonNode json = mapper.valueToTree(OpenAiProvider.buildBody(config, "hi", GenerationOverrides.none()));
        assertFalse(json.has("reasoning_effort"));
        assertTrue(json.get("max_completion_tokens").asInt() >= ReasoningModels.TOKEN_FLOOR);
    }

    @Test
    void localEndpointOmitsAuthorizationAndJoinsContentParts() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>("missing");
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"**Hi**"},{"type":"text","text":" there"}]}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            int port = server.getAddress().getPort();
            PluginConfig config = config("llama3.2", "", -1, 0, "", true, 0, 1, "low");
            YamlConfiguration yaml = yaml("llama3.2", "", -1, 0, "", true, 0, 1, "low");
            yaml.set("api.provider", "openai");
            yaml.set("api.base-url", "http://127.0.0.1:" + port + "/v1");
            yaml.set("api.key", "");
            config = new PluginConfig(yaml);

            OpenAiProvider provider = new OpenAiProvider(config, executor, Logger.getLogger("openai-test"));
            assertEquals("Hi there", provider.complete("hello").join());
            assertNull(authorization.get());
            assertFalse(body.get().contains("\"temperature\""));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void sendsBearerTokenWhenAKeyIsSet() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = """
                    {"choices":[{"message":{"role":"assistant","content":"pong"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            YamlConfiguration yaml = yaml("gpt-4o-mini", "", -1, 0, "", false, 0, 0, "low");
            yaml.set("api.key", "test-key");
            yaml.set("api.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiProvider provider = new OpenAiProvider(new PluginConfig(yaml), executor, Logger.getLogger("openai-auth"));
            assertEquals("pong", provider.complete("ping").join());
            assertEquals("Bearer test-key", authorization.get());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void blankContentIsAnErrorAndReasoningTextIsUsed() throws Exception {
        assertKind(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", AiErrorKind.OTHER);
        assertAnswer(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning_content\":\"The visible reasoning text.\"}}]}",
                "The visible reasoning text.");
        assertAnswer(200, "{\"choices\":[{\"message\":{\"content\":null,\"reasoning\":\"Only reasoning was produced.\"}}]}",
                "Only reasoning was produced.");
        assertAnswer(200, "{\"choices\":[{\"message\":{\"content\":\"final-answer\",\"reasoning_content\":\"hidden\"}}]}",
                "final-answer");
    }

    @Test
    void retryAfterHeaderIsCarriedOnTheException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"error\":{\"message\":\"rate limit\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "30");
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            YamlConfiguration yaml = yaml("gpt-4o-mini", "", -1, 0, "", false, 0, 0, "low");
            yaml.set("api.key", "test-key");
            yaml.set("api.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiProvider provider = new OpenAiProvider(new PluginConfig(yaml), executor, Logger.getLogger("openai-retry"));
            CompletionException error = assertThrows(CompletionException.class, () -> provider.complete("ping").join());
            assertEquals(30L, AiErrors.find(error).retryAfterSeconds());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void rateLimitAndUnknownModelAreClassified() throws Exception {
        assertKind(429, "{\"error\":{\"message\":\"rate limit\"}}", AiErrorKind.RATE_LIMIT);
        assertKind(402, "{\"error\":{\"message\":\"insufficient balance\"}}", AiErrorKind.QUOTA);
        assertKind(401, "{\"error\":{\"message\":\"invalid api key\"}}", AiErrorKind.BAD_KEY);
        assertKind(404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"no such model\"}}", AiErrorKind.UNKNOWN_MODEL);
    }

    private void assertAnswer(int status, String responseBody, String expected) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            YamlConfiguration yaml = yaml("o1-mini", "", -1, 0, "", false, 0, 0, "low");
            yaml.set("api.key", "test-key");
            yaml.set("api.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiProvider provider = new OpenAiProvider(new PluginConfig(yaml), executor, Logger.getLogger("openai-text"));
            assertEquals(expected, provider.complete("ping").join());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private void assertKind(int status, String responseBody, AiErrorKind expected) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            YamlConfiguration yaml = yaml("missing-model", "", -1, 0, "", false, 0, 0, "low");
            yaml.set("api.key", "test-key");
            yaml.set("api.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiProvider provider = new OpenAiProvider(new PluginConfig(yaml), executor, Logger.getLogger("openai-status"));
            CompletionException error = assertThrows(CompletionException.class, () -> provider.complete("ping").join());
            assertEquals(expected, AiErrors.classify(error));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static PluginConfig config(
            String model,
            String system,
            double temperature,
            int maxTokens,
            String ignored,
            boolean strip,
            int maxChars,
            int maxLines,
            String effort
    ) {
        return new PluginConfig(yaml(model, system, temperature, maxTokens, ignored, strip, maxChars, maxLines, effort));
    }

    private static YamlConfiguration yaml(
            String model,
            String system,
            double temperature,
            int maxTokens,
            String ignored,
            boolean strip,
            int maxChars,
            int maxLines,
            String effort
    ) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", model);
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.system-prompt", system);
        yaml.set("api.temperature", temperature);
        yaml.set("api.max-tokens", maxTokens);
        yaml.set("api.strip-markdown", strip);
        yaml.set("api.max-answer-chars", maxChars);
        yaml.set("api.max-answer-lines", maxLines);
        yaml.set("api.reasoning-effort", effort);
        return yaml;
    }
}
