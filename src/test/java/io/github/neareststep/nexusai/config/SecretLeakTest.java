package io.github.neareststep.nexusai.config;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.dialogue.DialogueProtocol;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
