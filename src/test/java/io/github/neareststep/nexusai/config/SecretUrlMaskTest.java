package io.github.neareststep.nexusai.config;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.event.EventDispatcher;
import io.github.neareststep.nexusai.event.EventSupport;
import io.github.neareststep.nexusai.i18n.MessageService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecretUrlMaskTest {

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

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
    void redactUrlMasksQueryValuesUserinfoAndKeepsPathHostAndPort() {
        List<String> none = List.of();
        assertEquals(
                "http://127.0.0.2:1/v1?key=***&other=***",
                SecretMask.redactUrl("http://127.0.0.2:1/v1?key=CANARY&other=val", none));
        assertEquals(
                "http://host/v1?empty=***",
                SecretMask.redactUrl("http://host/v1?empty=", none));
        assertEquals(
                "http://host/v1?***",
                SecretMask.redactUrl("http://host/v1?CANARY", none));
        assertEquals(
                "http://host/v1/CANARY/chat?key=***",
                SecretMask.redactUrl("http://host/v1/CANARY/chat?key=secret", none));
        assertEquals(
                "http://***@example.test:8443/v1?token=***",
                SecretMask.redactUrl("http://user:pass@example.test:8443/v1?token=secret", none));
        assertEquals(
                "http://[2001:db8::1]:8080/v1?key=***",
                SecretMask.redactUrl("http://[2001:db8::1]:8080/v1?key=CANARY", none));
        assertEquals(
                "http://host/path%20x?key=***",
                SecretMask.redactUrl("http://host/path%20x?key=secret", none));
        String broken = "http://[bad/v1?key=CANARY&x=1";
        String maskedBroken = SecretMask.redactUrl(broken, none);
        assertFalse(maskedBroken.contains("CANARY"), maskedBroken);
        assertTrue(maskedBroken.contains("key=***"), maskedBroken);
        assertTrue(maskedBroken.contains("x=***"), maskedBroken);
        assertEquals(
                "see http://host/v1?key=*** now.",
                SecretMask.redactUrl("see http://host/v1?key=CANARY now.", none));
        assertEquals(
                "ended http://host/v1?key=***).",
                SecretMask.redactUrl("ended http://host/v1?key=CANARY).", none));
    }

    @Test
    void statusBaseUrlShowsMaskedQueryWithAmpersandLiterally() {
        String canary = "CANARYQ";
        String url = "http://127.0.0.2:1/v1?key=" + canary + "&org=CANARY2";
        YamlConfiguration en = new YamlConfiguration();
        en.set("prefix", "");
        en.set("command.status-base-url", "&7Base URL: &f{base_url}");
        MessageService messages = MessageService.forTest(en, en, "en");
        String masked = SecretMask.redactUrl(url, List.of());
        String shown = PlainTextComponentSerializer.plainText().serialize(
                messages.component("command.status-base-url", Map.of("base_url", masked)));
        assertTrue(shown.contains("Base URL:"), shown);
        assertTrue(shown.contains("?key=***&org=***"), shown);
        assertFalse(shown.contains(canary), shown);
        assertFalse(shown.contains("CANARY2"), shown);
        assertFalse(NaiCommand.redact(url, List.of()).contains(canary));
    }

    @Test
    void ordinaryQueryValuesAreNotConfiguredSecrets() {
        List<String> secrets = new ArrayList<>();
        SecretMask.addUrlSecrets(
                "https://h/openai/deployments/x/chat"
                        + "?api-version=2024-06-01&model=gpt-4o-mini&stream=true&format=json&key=CANARYQ",
                secrets);
        assertEquals(List.of("CANARYQ"), secrets);
        String reply = "Use gpt-4o-mini, released 2024-06-01, stream true.";
        assertEquals(reply, SecretMask.redact(reply, secrets));

        List<String> modelSecrets = new ArrayList<>();
        for (String modelUrl : List.of(
                "https://h/v1?model=meta-llama/Llama-3.1-8B",
                "https://h/v1?model=meta-llama%2FLlama-3.1-8B-Instruct",
                "https://h/v1?model=Qwen2.5-72B-Instruct")) {
            SecretMask.addUrlSecrets(modelUrl, modelSecrets);
        }
        assertTrue(modelSecrets.isEmpty(), modelSecrets.toString());
        String modelReply = "meta-llama/Llama-3.1-8B, Qwen2.5-72B-Instruct";
        assertEquals(modelReply, SecretMask.redact(modelReply, modelSecrets));

        List<String> named = new ArrayList<>();
        SecretMask.addUrlSecrets(
                "https://h/v1?key=CANARYQ&api_key=ApiKeyCanary1&token=TokenCanary12"
                        + "&sig=SigCanaryValue&x-goog-api-key=GoogKeyCanary1"
                        + "&AbCdEf1234567890xyzQ",
                named);
        assertTrue(named.contains("CANARYQ"), named.toString());
        assertTrue(named.contains("ApiKeyCanary1"), named.toString());
        assertTrue(named.contains("TokenCanary12"), named.toString());
        assertTrue(named.contains("SigCanaryValue"), named.toString());
        assertTrue(named.contains("GoogKeyCanary1"), named.toString());
        assertTrue(named.contains("AbCdEf1234567890xyzQ"), named.toString());

        List<String> userinfo = new ArrayList<>();
        SecretMask.addUrlSecrets("https://admin:PassCanary99@h/v1?model=gpt-4o-mini", userinfo);
        assertTrue(userinfo.contains("PassCanary99"), userinfo.toString());
        assertFalse(userinfo.contains("admin"), userinfo.toString());
        assertFalse(userinfo.contains("gpt-4o-mini"), userinfo.toString());
    }

    @Test
    void http500BodyAndDiagnosticsHideUrlQueryCanaries() throws Exception {
        String canary = "CANARYQ";
        String canary2 = "CANARY2";
        String encoded = "CAN%2FARY3";
        String decoded = "CAN/ARY3";
        List<String> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("url-body-" + UUID.randomUUID().toString().substring(0, 8));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = capturing(captured);
        logger.addHandler(handler);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String echo = "boom at /v1/chat/completions?key=" + canary
                    + "&token=" + canary2 + "&sig=" + encoded;
            byte[] body = ("{\"error\": {\"message\": \"" + echo + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        executor = Executors.newSingleThreadExecutor();
        support = EventSupport.register("UrlSecrets");
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1?key=" + canary + "&token=" + canary2 + "&sig=" + encoded;
            PluginConfig config = providerConfig(baseUrl);
            assertTrue(config.configuredSecrets().contains(canary));
            assertTrue(config.configuredSecrets().contains(canary2));
            assertTrue(config.configuredSecrets().contains(encoded));
            assertTrue(config.configuredSecrets().contains(decoded));

            EventDispatcher.install(EventDispatcher.create(
                    logger,
                    config::configuredSecrets,
                    event -> {
                        support.events.add(event);
                        captured.add(event.toString());
                    },
                    System::nanoTime,
                    () -> false));

            AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofMillis(1), config::configuredSecrets);
            ModelQueue queue = new ModelQueue(
                    config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                    LocalDate::now, ZoneId.of("UTC"), logger);
            OpenAiProvider http = new OpenAiProvider(config, executor, logger);
            RoutingProvider provider = new RoutingProvider(config, queue, http, executor, logger);
            AiHttpClient client = new AiHttpClient(
                    new AiCache(Duration.ofMinutes(5), 10),
                    provider,
                    config,
                    RequestGate.permissive(),
                    diagnostics,
                    logger);
            CallTrace trace = CallTrace.start(RequestOrigin.PLACEHOLDER, null, "probe", "");
            assertTrue(client.requestAsync("ping", null, GenerationOverrides.none(), null, null, trace)
                    .handle((text, error) -> error != null)
                    .get(5, TimeUnit.SECONDS));

            captured.add(diagnostics.lastError() == null ? "" : diagnostics.lastError());
            captured.add(NaiCommand.redact(
                    diagnostics.lastError() == null ? "" : diagnostics.lastError(),
                    config.configuredSecrets()));

            NexusProviderErrorEvent errorEvent = null;
            for (Event event : support.events) {
                if (event instanceof NexusProviderErrorEvent providerError) {
                    errorEvent = providerError;
                    captured.add(providerError.message());
                }
            }
            assertInstanceOf(NexusProviderErrorEvent.class, errorEvent);

            for (String line : captured) {
                assertFalse(line != null && line.contains(canary), line);
                assertFalse(line != null && line.contains(canary2), line);
                assertFalse(line != null && line.contains(encoded), line);
                assertFalse(line != null && line.contains(decoded), line);
            }
            assertTrue(captured.stream().anyMatch(line -> line != null && line.contains("HTTP 500")),
                    captured.toString());
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    void providerErrorAndFineLogHideAQueryCanary() throws Exception {
        String canary = "CANARY-err-" + UUID.randomUUID().toString().replace("-", "");
        List<String> captured = new ArrayList<>();
        Logger logger = Logger.getLogger("url-mask-" + canary.substring(canary.length() - 8));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        Handler handler = capturing(captured);
        logger.addHandler(handler);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            assertEquals("key=" + canary, exchange.getRequestURI().getQuery());
            byte[] body = "{\"error\":{\"message\":\"nope\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        executor = Executors.newSingleThreadExecutor();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1?key=" + canary;
            PluginConfig config = providerConfig(baseUrl);
            OpenAiProvider provider = new OpenAiProvider(config, executor, logger);
            try {
                provider.exchange("ping", null, baseUrl, "test-key", "gpt-test");
            } catch (RuntimeException ignored) {
                // Expected HTTP 401.
            }
            String syntax = new URISyntaxException(baseUrl, "bad").getMessage();
            captured.add(SecretMask.redactUrl(syntax, config.configuredSecrets()));
            AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofMillis(1), config::configuredSecrets);
            diagnostics.report(AiErrorKind.OTHER, "HTTP failed calling " + baseUrl, false);
            captured.add(diagnostics.lastError());
            for (String line : captured) {
                assertFalse(line != null && line.contains(canary), line);
            }
            assertTrue(captured.stream().anyMatch(line -> line != null && line.contains("key=***")),
                    captured.toString());
        } finally {
            logger.removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void actionsAndModerationLogsAreOwnerReadWrite(@TempDir Path dir) throws Exception {
        assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null,
                "POSIX permissions are not available");
        Path actions = dir.resolve("actions.log");
        Path moderation = dir.resolve("moderation.log");
        Logger silent = Logger.getLogger("private-logs");
        silent.setUseParentHandlers(false);

        io.github.neareststep.nexusai.dialogue.ActionLog actionLog =
                new io.github.neareststep.nexusai.dialogue.ActionLog(silent, actions.toFile(), () -> true);
        actionLog.record("Steve", "guide", "wave", "ran");
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(actions));

        Set<PosixFilePermission> loose = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(actions, loose);
        actionLog.record("Steve", "guide", "wave", "ran");
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(actions));

        Files.move(actions, dir.resolve("actions.log.1"));
        actionLog.record("Steve", "guide", "wave", "ran");
        assertTrue(Files.exists(actions));
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(actions));

        io.github.neareststep.nexusai.moderation.ModerationLog moderationLog =
                new io.github.neareststep.nexusai.moderation.ModerationLog(moderation.toFile(), silent);
        moderationLog.append(UUID.randomUUID(), "Steve", "hi", "spam", "test");
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(moderation));
        Files.setPosixFilePermissions(moderation, loose);
        moderationLog.append(UUID.randomUUID(), "Steve", "hi", "spam", "test");
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(moderation));
        Files.move(moderation, dir.resolve("moderation.log.1"));
        moderationLog.append(UUID.randomUUID(), "Steve", "hi", "spam", "test");
        assertTrue(Files.exists(moderation));
        assertEquals(OWNER_ONLY, Files.getPosixFilePermissions(moderation));
    }

    private static PluginConfig providerConfig(String baseUrl) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-test");
        yaml.set("api.base-url", baseUrl);
        yaml.set("api.key", "test-key");
        yaml.set("api.connect-timeout", 2);
        yaml.set("api.read-timeout", 2);
        yaml.set("fallback", "...");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", baseUrl);
        yaml.set("providers.openai.api-key", "test-key");
        yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-test")));
        return new PluginConfig(yaml);
    }

    private static Handler capturing(List<String> captured) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record.getLevel() + " " + record.getMessage());
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        captured.add(String.valueOf(parameter));
                    }
                }
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
        return handler;
    }
}
