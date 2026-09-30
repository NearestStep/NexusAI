package io.github.neareststep.nexusai.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackOrderingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mockServerTriesTheQueueBeforeTheFallbackModel() throws Exception {
        List<String> models = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = mapper.readTree(body).path("model").asText();
            models.add(model);
            int status = "llama3.2".equals(model) ? 200 : 500;
            String payload = status == 200
                    ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fallback-ok\"}}]}"
                    : "{\"error\":\"down\"}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PluginConfig config = localConfig(server.getAddress().getPort());
            ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), new AtomicLong(5_000L));
            OpenAiProvider http = new OpenAiProvider(config, executor, Logger.getLogger("fallback-http"));
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("fallback-route"), () -> 5_000L);
            GenerationOverrides overrides = GenerationOverrides.none().withFallbackModel("ollama", "llama3.2");
            assertEquals("fallback-ok", provider.complete("ping", overrides).join());
            assertEquals(List.of("gpt-4o-mini", "llama3.2"), models);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void mockServerDoesNotCallTheFallbackWhenTheQueueAnswers() throws Exception {
        List<String> models = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            models.add(mapper.readTree(body).path("model").asText());
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"queue-ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PluginConfig config = localConfig(server.getAddress().getPort());
            ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), new AtomicLong(5_000L));
            OpenAiProvider http = new OpenAiProvider(config, executor, Logger.getLogger("fallback-skip"));
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("fallback-skip"), () -> 5_000L);
            GenerationOverrides overrides = GenerationOverrides.none().withFallbackModel("ollama", "llama3.2");
            assertEquals("queue-ok", provider.complete("ping", overrides).join());
            assertEquals(List.of("gpt-4o-mini"), models);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedFallbackReplyIsDiscardedByTheSameFilter() throws Exception {
        String echo = PlayerInput.GUARD;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = mapper.readTree(body).path("model").asText();
            String payload = "llama3.2".equals(model)
                    ? "{\"choices\":[{\"message\":{\"content\":" + mapper.writeValueAsString(echo) + "}}]}"
                    : "{\"error\":\"down\"}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders("llama3.2".equals(model) ? 200 : 500, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            PluginConfig config = localConfig(server.getAddress().getPort());
            AtomicLong clock = new AtomicLong(8_000L);
            ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), clock);
            OpenAiProvider http = new OpenAiProvider(config, executor, Logger.getLogger("fallback-reject"));
            RoutingProvider provider = new RoutingProvider(
                    config, queue, http, executor, Logger.getLogger("fallback-reject"), clock::get);
            GenerationOverrides overrides = GenerationOverrides.none().withFallbackModel("ollama", "llama3.2");
            CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class, () -> provider.complete("ping", overrides).join());
            assertEquals(AiErrorKind.REJECTED, AiErrors.classify(error));
            assertEquals(1, queue.fallbackStatus("ollama", "llama3.2", clock.get()).rejected());
            assertFalse(queue.fallbackStatus("ollama", "llama3.2", clock.get()).state().startsWith("COOLDOWN"));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void unavailableQueueUsesTheFallbackOnce() {
        AtomicLong clock = new AtomicLong(1_000L);
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            models.add(model);
            return new ChatExchange("local", Map.of());
        };
        ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 1)), clock);
        assertTrue(queue.tryConsume(0, clock.get()));
        RoutingProvider provider = provider(queue, clock, http);
        assertEquals("local", provider.complete("ping", GenerationOverrides.none().withFallbackModel("ollama", "llama3.2")).join());
        assertEquals(List.of("llama3.2"), models);
    }

    @Test
    void fallbackRespectsTheMatchingQueueDailyCap() {
        AtomicLong clock = new AtomicLong(2_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange("no", Map.of());
        };
        ModelQueue queue = queue(List.of(new QueueEntryConfig("ollama", "llama3.2", 1)), clock);
        assertTrue(queue.tryConsume(0, clock.get()));
        RoutingProvider provider = provider(queue, clock, http);
        CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> provider.complete("ping", GenerationOverrides.none().withFallbackModel("ollama", "llama3.2")).join());
        assertEquals(AiErrorKind.LOCAL_LIMIT, AiErrors.classify(error));
        assertEquals(0, calls.get());
    }

    @Test
    void fallbackCooldownBlocksTheNextCall() {
        AtomicLong clock = new AtomicLong(3_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.OTHER, 500, "down", null);
            }
            throw new AiRequestException(AiErrorKind.TIMEOUT, 0, "fallback timed out", null);
        };
        ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), clock);
        RoutingProvider provider = provider(queue, clock, http);
        GenerationOverrides overrides = GenerationOverrides.none().withFallbackModel("ollama", "llama3.2");
        AiRequestException first = failure(provider, overrides);
        assertEquals(AiErrorKind.TIMEOUT, first.kind());
        assertEquals(2, calls.get());
        assertTrue(queue.fallbackStatus("ollama", "llama3.2", clock.get()).state().startsWith("COOLDOWN"));

        failure(provider, overrides);
        assertEquals(2, calls.get());

        clock.set(3_000L + 60_000L);
        failure(provider, overrides);
        assertEquals(4, calls.get());
    }

    @Test
    void providerWideDailyCapBlocksADifferentFallbackModel() {
        AtomicLong clock = new AtomicLong(4_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange("no", Map.of());
        };
        ModelQueue queue = queue(List.of(new QueueEntryConfig("ollama", "big", 1)), clock);
        assertTrue(queue.tryConsume(0, clock.get()));
        RoutingProvider provider = provider(queue, clock, http);
        CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class,
                () -> provider.complete("ping", GenerationOverrides.none().withFallbackModel("ollama", "small")).join());
        assertEquals(AiErrorKind.LOCAL_LIMIT, AiErrors.classify(error));
        assertEquals(0, calls.get());
    }

    @Test
    void statusLineNamesTheFallbackModel() {
        ModelQueue queue = queue(List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)), new AtomicLong(9_000L));
        ModelQueue.Status row = queue.fallbackStatus("ollama", "llama3.2", 9_000L);
        assertEquals("ollama", row.provider());
        assertEquals("llama3.2", row.model());
        assertEquals("READY", row.state());
    }

    private static AiRequestException failure(RoutingProvider provider, GenerationOverrides overrides) {
        CompletionException error = org.junit.jupiter.api.Assertions.assertThrows(
                CompletionException.class, () -> provider.complete("ping", overrides).join());
        AiRequestException typed = AiErrors.find(error);
        org.junit.jupiter.api.Assertions.assertNotNull(typed);
        return typed;
    }

    private static RoutingProvider provider(ModelQueue queue, AtomicLong clock, ChatCaller http) {
        return new RoutingProvider(
                localConfig(9),
                queue,
                http,
                Executors.newSingleThreadExecutor(),
                Logger.getLogger("fallback-unit"),
                clock::get);
    }

    private static ModelQueue queue(List<QueueEntryConfig> entries, AtomicLong clock) {
        return new ModelQueue(
                entries,
                0,
                60_000L,
                300_000L,
                null,
                clock::get,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("fallback-queue"));
    }

    private static PluginConfig localConfig(int port) {
        String url = "http://127.0.0.1:" + port + "/v1";
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "test-key");
        yaml.set("fallback", "...");
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.auth-pause-seconds", 300);
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", url);
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("providers.ollama.type", "openai-compatible");
        yaml.set("providers.ollama.url", url);
        yaml.set("providers.ollama.api-key", "");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        yaml.set("fallback-model.provider", "ollama");
        yaml.set("fallback-model.model", "llama3.2");
        return new PluginConfig(yaml);
    }
}
