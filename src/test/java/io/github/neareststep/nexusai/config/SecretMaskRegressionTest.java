package io.github.neareststep.nexusai.config;

import com.sun.net.httpserver.HttpServer;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.dialogue.DialogueTransport;
import io.github.neareststep.nexusai.dialogue.MemoryStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NAI-68, NAI-69, and NAI-73. The eight channels are the NPC reply, the placeholder answer,
 * {@code /nai test}, the server log, in-memory user and assistant lines, and those same lines in
 * {@code dialogue-memory.yml}.
 */
class SecretMaskRegressionTest {

    private static final List<String> CAMEL = List.of(
            "sk-NexusAIPluginForMinecraft2026",
            "sk-OpenAIStyleKeyExample123",
            "sk-PaperMCBuildForRelease2026",
            "sk-MyAPIClientForServers2026");

    @Test
    void camelCaseAbbreviationAfterSkIsLeftAsWritten() {
        for (String token : CAMEL) {
            assertEquals("see " + token + " now", SecretMask.redact("see " + token + " now", List.of()));
            assertEquals(token, SecretMask.redact(token, List.of()));
        }
    }

    @Test
    void singleCaseVendorTemplatesAreMaskedAndBoundariesStayVisible() {
        String deepSeek = "sk-" + "ab".repeat(15) + "01";
        String deepSeekUpper = "sk-" + "AB".repeat(15) + "01";
        String openRouter = "sk-or-v1-" + "cd".repeat(32);
        String openRouterUpper = "sk-or-v1-" + "CD".repeat(32);
        String groq = "gsk_" + "ef".repeat(26);
        String groqUpper = "gsk_" + "EF".repeat(26);
        String anthropic = "sk-ant-api03-abc123xyz-456def789ghij-klmnopqrstuvwx-3456yza789bcde-1234fghijklmnopby56aaaogaopaaaabc123xyzaa";
        String admin = "sk-ant-admin01-abc12fake-456def789ghij-klmnopqrstuvwx-3456yza789bcde-12fakehijklmnopby56aaaogaopaaaabc123xyzaa";
        assertEquals(32, deepSeek.substring(3).length());
        assertEquals(64, openRouter.substring("sk-or-v1-".length()).length());
        assertEquals(52, groq.substring(4).length());
        assertEquals(56, groq.length());
        assertEquals(93, anthropic.substring("sk-ant-api03-".length(), anthropic.length() - 2).length());
        assertEquals(93, admin.substring("sk-ant-admin01-".length(), admin.length() - 2).length());

        for (String token : List.of(deepSeek, deepSeekUpper, openRouter, openRouterUpper, groq, groqUpper, anthropic, admin)) {
            String masked = SecretMask.redact("see " + token + " now", List.of());
            assertFalse(masked.contains(token), masked);
            assertTrue(masked.contains("****" + token.substring(token.length() - 4)), masked);
        }
        String russian = "Ключ " + deepSeek + " лежит в сундуке, а " + openRouter + " нет.";
        String russianMasked = SecretMask.redact(russian, List.of());
        assertFalse(russianMasked.contains(deepSeek), russianMasked);
        assertFalse(russianMasked.contains(openRouter), russianMasked);
        assertTrue(russianMasked.contains("сундуке"), russianMasked);

        String inUrl = "https://example.com/download/" + deepSeek + ".zip";
        String afterHyphen = "note-" + openRouter;
        assertFalse(SecretMask.redact(inUrl, List.of()).contains(deepSeek));
        assertTrue(SecretMask.redact(inUrl, List.of()).contains("****" + deepSeek.substring(deepSeek.length() - 4) + ".zip"));
        assertEquals("note-****" + openRouter.substring(openRouter.length() - 4), SecretMask.redact(afterHyphen, List.of()));

        List<String> keep = List.of(
                "sk-" + "ab".repeat(15) + "0",
                "sk-" + "ab".repeat(16) + "0",
                "sk-" + "AB".repeat(15) + "0",
                "sk-" + "AB".repeat(16) + "0",
                "sk-deadbeef",
                "sk-cafebabe",
                "sk-feedback",
                "sk-deadbeefcafebabe",
                "sk-or-v1-" + "ab".repeat(31) + "c",
                "sk-or-v1-" + "ab".repeat(32) + "c",
                "gsk_" + "a".repeat(48),
                "gsk_" + "a".repeat(51),
                "gsk_" + "a".repeat(53),
                "gsk_" + "A".repeat(48),
                "gsk_" + "A".repeat(51),
                "gsk_" + "A".repeat(53),
                "sk-ant-api03-" + "a".repeat(91) + "1aa",
                "sk-ant-api03-" + "a".repeat(93) + "1aa",
                "sk-ant-api03-" + "a".repeat(92) + "1ab",
                "sk-learn-pipeline-v2-2024-final",
                "task-1234567890abcdefghij",
                "desk-1234567890abcdefghijk",
                "kiosk-2024-terminal-config-v12-final",
                "sk-hynix-2024-q3-earnings-report");
        for (String line : keep) {
            assertEquals(line, SecretMask.redact(line, List.of()), line);
        }
    }

    @Test
    void vendorKeyBeforeUnderscoreKeepsTheSeparatorAndAnInternalUnderscoreStaysInside() {
        String configured = "sk-qaConfiguredKey1111";
        String vendor = "sk-qaUnconfigured9999zz";
        String other = "qa-ring-kA-0002y";
        String path = "kp_" + configured + "_" + vendor + "_" + other + "/plugins";
        assertEquals("kp_****1111_****99zz_****002y/plugins",
                SecretMask.redact(path, List.of(configured, other)));

        assertEquals("see ****99zz_plugins now", SecretMask.redact("see " + vendor + "_plugins now", List.of()));
        assertEquals("see ****99zz_Plugin now", SecretMask.redact("see " + vendor + "_Plugin now", List.of()));
        assertEquals("see ****99zz_ now", SecretMask.redact("see " + vendor + "_ now", List.of()));

        String internal = "sk-AbCdEfGh12_IjKlMnOpQr99zz";
        String masked = SecretMask.redact("see " + internal + " now", List.of());
        assertFalse(masked.contains(internal), masked);
        assertFalse(masked.contains("IjKl"), masked);
        assertFalse(masked.contains("AbCd"), masked);
        assertTrue(masked.contains("****99zz"), masked);
        assertFalse(masked.contains("99zz_"), masked);
    }

    @Test
    void camelCaseAndSingleCaseKeysHoldOnEveryOutputChannel() throws Exception {
        String camel = "sk-NexusAIPluginForMinecraft2026";
        String deepSeek = "sk-" + "ab".repeat(15) + "01";
        String maskedTail = "****" + deepSeek.substring(deepSeek.length() - 4);

        for (String token : List.of(camel, deepSeek)) {
            String expected = token.equals(camel) ? token : maskedTail;
            assertChannel(token, expected, "see " + token + " now");
        }

        Path dir = Files.createTempDirectory("secret-mask-channels");
        Path file = dir.resolve("dialogue-memory.yml");
        UUID player = UUID.randomUUID();
        Files.writeString(file, """
                entries:
                  %s:
                    blacksmith:
                      updated: 50
                      lines:
                      - role: user
                        text: "user %s %s"
                      - role: assistant
                        text: "assistant %s %s"
                """.formatted(player, camel, deepSeek, camel, deepSeek), StandardCharsets.UTF_8);
        MemoryStore store = new MemoryStore();
        store.load(file.toFile(), 60L, 10_000L, Logger.getLogger("mask-channels"), List.of());
        String saved = Files.readString(file);
        assertTrue(saved.contains(camel), saved);
        assertFalse(saved.contains(deepSeek), saved);
        assertTrue(saved.contains(maskedTail), saved);
        assertTrue(saved.contains("user " + camel), saved);
        assertTrue(saved.contains("assistant " + camel), saved);
        var lines = store.transcript(player, "blacksmith", 60L, 8, 10_000, 10_000L);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).text().contains(camel), lines.get(0).text());
        assertTrue(lines.get(1).text().contains(camel), lines.get(1).text());
        assertFalse(lines.get(0).text().contains(deepSeek), lines.get(0).text());
        assertFalse(lines.get(1).text().contains(deepSeek), lines.get(1).text());
        assertTrue(lines.get(0).text().contains(maskedTail), lines.get(0).text());
        assertTrue(lines.get(1).text().contains(maskedTail), lines.get(1).text());
    }

    private static void assertChannel(String token, String expectedPiece, String sentence) throws Exception {
        assertTrue(SecretMask.redact(sentence, List.of()).contains(expectedPiece), "placeholder " + token);
        assertTrue(NaiCommand.redact(sentence, List.of()).contains(expectedPiece), "/nai test " + token);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "http://127.0.0.1");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        PluginConfig config = new PluginConfig(yaml);
        DialogueTransport transport = new DialogueTransport(() -> config, HttpClient.newHttpClient());
        String npc = transport.finishText(sentence, "hello", "chat", "");
        assertTrue(npc.contains(expectedPiece), npc);

        Logger logger = Logger.getLogger("mask-channel-" + token.substring(token.length() - 6));
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<String> warnings = new java.util.ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING && record.getMessage() != null) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), List::of);
        diagnostics.report(AiErrorKind.OTHER, sentence, false);
        assertTrue(warnings.stream().anyMatch(line -> line.contains(expectedPiece)), warnings.toString());
        assertTrue(warnings.stream().noneMatch(line -> token.equals(expectedPiece) ? false : line.contains(token)),
                warnings.toString());

        AtomicReference<String> reply = new AtomicReference<>(sentence);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                    + quote(reply.get()) + "}}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            yaml.set("api.base-url", base);
            PluginConfig live = new PluginConfig(yaml);
            OpenAiProvider provider = new OpenAiProvider(live, executor, Logger.getLogger("mask-http"));
            String text = provider.exchange("ping", null, base, "", "gpt-4o-mini").text();
            assertTrue(text.contains(expectedPiece), text);
            if (!token.equals(expectedPiece)) {
                assertFalse(text.contains(token), text);
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
