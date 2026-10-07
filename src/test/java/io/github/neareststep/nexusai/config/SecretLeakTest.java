package io.github.neareststep.nexusai.config;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.GenerationResult;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.ResultSource;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.dialogue.DialogueProtocol;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import io.github.neareststep.nexusai.generate.GenerationRuntime;
import io.github.neareststep.nexusai.generate.GenerationService;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretLeakTest {

    @TempDir
    Path dir;

    @Test
    void aCanaryKeyNeverAppearsInLogsErrorsOrCommandText() throws Exception {
        String canary = "sk-canary-" + UUID.randomUUID().toString().replace("-", "");
        List<String> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("secret-leak-" + canary.substring(canary.length() - 8));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getLevel() + " " + record.getMessage());
                if (record.getThrown() != null) {
                    captured.add(record.getThrown().toString());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);

        AtomicReference<String> mode = new AtomicReference<>("401");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("timeout".equals(mode.get())) {
                try {
                    Thread.sleep(2_500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = ("{\"error\":{\"message\":\"bad " + canary + "\"}}").getBytes(StandardCharsets.UTF_8);
            int status = "429".equals(mode.get()) ? 429 : 401;
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Function<String, String> previousEnv = PluginConfig.environment;
        Path previousBase = PluginConfig.secretsBase;
        try {
            Path keyFile = dir.resolve("secrets/groq.key");
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, canary + "\n", StandardCharsets.UTF_8);
            PluginConfig.secretsBase = dir;
            PluginConfig.environment = name -> switch (name) {
                case "NEXUSAI_API_KEY", "GROQ_API_KEY" -> canary;
                default -> null;
            };

            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("api.provider", "groq");
            yaml.set("api.model", "llama");
            yaml.set("api.base-url", baseUrl);
            yaml.set("api.key", "");
            yaml.set("api.connect-timeout", 1);
            yaml.set("api.read-timeout", 1);
            yaml.set("fallback", "...");
            yaml.set("providers.groq.type", "openai-compatible");
            yaml.set("providers.groq.url", baseUrl);
            yaml.set("providers.groq.api-key", "${ENV:GROQ_API_KEY}");
            yaml.set("providers.groq.api-key-file", "secrets/groq.key");
            PluginConfig config = new PluginConfig(yaml);
            captured.addAll(config.keyFileWarnings());
            captured.add(config.maskedApiKeys());
            assertEqualsFileSource(config);
            assertTrue(config.provider("groq").apiKeys().contains(canary));

            OpenAiProvider provider = new OpenAiProvider(config, executor, logger);
            capture(captured, () -> provider.exchange("ping", null, baseUrl, canary, "llama"));
            mode.set("429");
            capture(captured, () -> provider.exchange("ping", null, baseUrl, canary, "llama"));
            mode.set("timeout");
            capture(captured, () -> provider.exchange("ping", null, baseUrl, canary, "llama"));

            DialogueTransport transport = new DialogueTransport(() -> config, HttpClient.newHttpClient());
            mode.set("401");
            capture(captured, () -> transport.send(request(baseUrl, canary)));
            mode.set("429");
            capture(captured, () -> transport.send(request(baseUrl, canary)));
            mode.set("timeout");
            capture(captured, () -> transport.send(request(baseUrl, canary)));

            AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofMillis(1), () -> List.of(canary));
            diagnostics.report(AiErrorKind.BAD_KEY, "HTTP 401 unauthorized. The API key was rejected: " + canary, false);
            diagnostics.report(AiErrorKind.RATE_LIMIT, "HTTP 429 rate limit from example: " + canary, true);
            diagnostics.report(AiErrorKind.TIMEOUT, "Request timed out calling example " + canary, false);
            captured.add(diagnostics.lastError());

            captured.add(NaiCommand.redact("status " + canary + " failed", List.of(canary)));
            captured.add(NaiCommand.reloadFailureText(new IllegalStateException("reload failed " + canary), List.of(canary)));

            for (String line : captured) {
                assertFalse(line != null && line.contains(canary), line);
            }
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
            executor.shutdownNow();
            PluginConfig.environment = previousEnv;
            PluginConfig.secretsBase = previousBase;
        }
    }

    @Test
    void generationResultFieldsHideTheCanaryOn401429And500() throws Exception {
        String canary = "sk-canary-" + UUID.randomUUID().toString().replace("-", "");
        List<String> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("secret-result-" + canary.substring(canary.length() - 8));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getLevel() + " " + record.getMessage());
                if (record.getThrown() != null) {
                    captured.add(String.valueOf(record.getThrown()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);

        AtomicInteger status = new AtomicInteger(401);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"error\":{\"message\":\"bad " + canary + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "1");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Plugin owner = ownerPlugin("Quests");
            for (int code : List.of(401, 429, 500)) {
                status.set(code);
                PluginConfig config = canaryConfig(baseUrl, canary);
                GenerationService service = generationService(config, executor, logger);
                GenerationResult result = service.generate(owner, GenerationRequest.template("ping").build())
                        .get(10, TimeUnit.SECONDS);
                captured.add(resultFields(result));
                assertFalse(result.success());
                assertEquals(ResultSource.FALLBACK, result.source());
                assertFalse(result.text().contains(canary));
                if (code == 401) {
                    assertEquals(NexusErrorKind.BAD_KEY, result.error().orElseThrow().kind());
                    assertEquals(401, result.error().orElseThrow().httpStatus());
                } else if (code == 429) {
                    assertEquals(NexusErrorKind.RATE_LIMIT, result.error().orElseThrow().kind());
                    assertEquals(429, result.error().orElseThrow().httpStatus());
                } else {
                    assertEquals(NexusErrorKind.PROVIDER_ERROR, result.error().orElseThrow().kind());
                    assertEquals(500, result.error().orElseThrow().httpStatus());
                }
                service.shutdown();
            }
            for (String line : captured) {
                assertFalse(line != null && line.contains(canary), line);
            }
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static String resultFields(GenerationResult result) {
        StringBuilder out = new StringBuilder();
        out.append(result).append('\n');
        out.append(result.text()).append('\n');
        out.append(result.promptId()).append('\n');
        out.append(result.providerId()).append('\n');
        out.append(result.model()).append('\n');
        out.append(result.finishReason()).append('\n');
        out.append(result.label()).append('\n');
        out.append(result.usage()).append('\n');
        result.error().ifPresent(error -> out.append(error).append('\n')
                .append(error.kind()).append('\n')
                .append(error.message()).append('\n')
                .append(error.httpStatus()).append('\n')
                .append(error.retryAfterSeconds()).append('\n'));
        return out.toString();
    }

    private static PluginConfig canaryConfig(String baseUrl, String canary) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", canary);
        yaml.set("api.connect-timeout", 2);
        yaml.set("api.read-timeout", 2);
        yaml.set("fallback", "busy");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", baseUrl);
        yaml.set("providers.openai.api-key", canary);
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
        return new PluginConfig(yaml);
    }

    private static GenerationService generationService(PluginConfig config, ExecutorService executor, Logger logger) {
        ModelQueue queue = new ModelQueue(
                config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                LocalDate::now, ZoneId.of("UTC"), logger);
        OpenAiProvider http = new OpenAiProvider(config, executor, logger);
        RoutingProvider provider = new RoutingProvider(config, queue, http, executor, logger);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10);
        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), config::configuredSecrets);
        RequestGate gate = RequestGate.permissive();
        AiHttpClient client = new AiHttpClient(cache, provider, config, gate, diagnostics, logger);
        GenerationService service = new GenerationService(ownerPlugin("NexusAI"), executor, executor, logger);
        service.publish(new GenerationRuntime(
                config, cache, gate, diagnostics, provider, client,
                PromptCatalog.empty(), KnowledgeBase.empty(), null));
        return service;
    }

    private static Plugin ownerPlugin(String name) {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger(name);
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static void assertEqualsFileSource(PluginConfig config) {
        assertTrue(config.provider("groq").keySource() == KeySource.FILE);
        assertTrue(config.maskedApiKeys().endsWith("(file)"));
    }

    private static DialogueTransport.Request request(String baseUrl, String apiKey) {
        return new DialogueTransport.Request(
                baseUrl,
                apiKey,
                "llama",
                "system",
                List.of(new DialogueProtocol.MemoryLine("user", "hi")),
                List.of(),
                null,
                16,
                null,
                null,
                Duration.ofSeconds(1));
    }

    private static void capture(List<String> captured, Runnable call) {
        try {
            call.run();
            captured.add("call returned");
        } catch (AiRequestException error) {
            captured.add(error.getMessage());
            captured.add(String.valueOf(error));
            if (error.getCause() != null) {
                captured.add(String.valueOf(error.getCause()));
            }
        } catch (RuntimeException error) {
            captured.add(error.toString());
            if (error.getCause() != null) {
                captured.add(String.valueOf(error.getCause()));
            }
        }
    }
}
