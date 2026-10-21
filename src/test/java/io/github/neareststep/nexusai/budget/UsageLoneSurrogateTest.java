package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lone UTF-16 surrogates in provider and model names: {@code token-usage.yml} writes U+FFFD, and
 * {@code usage.yml} keeps one row per model across restarts.
 */
class UsageLoneSurrogateTest {

    private static final String HALF = "\ud83d";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);

    @Test
    void tokenUsageFileWritesReplacementAndEscapesNewlines() throws Exception {
        Map<String, TokenLedger.Counts> fallback = new LinkedHashMap<>();
        fallback.put("groq|qa-fb" + HALF, new TokenLedger.Counts(1, 10, 5, 15, 0));
        fallback.put("groq|qa-fb\uFFFD", new TokenLedger.Counts(2, 20, 10, 30, 1));
        fallback.put("a\nb", new TokenLedger.Counts(1, 1, 1, 2, 0));
        TokenLedger.Snapshot snapshot = new TokenLedger.Snapshot(DAY, TokenLedger.Counts.zero(), Map.of(),
                Map.of(), fallback, Map.of(), Map.of(), Map.of(), List.of());

        String text = TokenUsageFile.render(snapshot, OffsetDateTime.of(2026, 10, 21, 9, 0, 0, 0, ZoneOffset.UTC), List.of());
        // Strict UTF-8: a lone surrogate would throw here instead of turning into '?'.
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        String written = StandardCharsets.UTF_8.decode(bytes).toString();
        assertTrue(written.contains("\"groq|qa-fb\uFFFD\":"), written);
        assertFalse(written.contains("qa-fb?"), written);
        assertTrue(written.contains("\"a\\nb\":"), written);

        TokenLedger.Snapshot parsed = TokenUsageFile.parse(written);
        assertEquals(2, parsed.fallback().size(), parsed.fallback().toString());
        TokenLedger.Counts merged = parsed.fallback().get("groq|qa-fb\uFFFD");
        assertEquals(new TokenLedger.Counts(3, 30, 15, 45, 1), merged);
        assertEquals(new TokenLedger.Counts(1, 1, 1, 2, 0), parsed.fallback().get("a\nb"));
    }

    @Test
    void fallbackModelWithALoneSurrogateKeepsOneRowAcrossRestarts() throws Exception {
        Path file = Files.createTempDirectory("nexusai-usage-lone-restart").resolve("usage.yml");
        String model = "qa-fb" + HALF;
        for (int run = 0; run < 3; run++) {
            ModelQueue queue = queue(file, List.of());
            assertTrue(queue.tryConsumeFallback("groq", model, 1_000L));
            assertEquals(run + 1, queue.fallbackStatus("groq", model, 1_000L).requestsToday());
            queue.flushAndClose();
        }
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        List<Map<?, ?>> rows = saved.getMapList("fallback");
        assertEquals(1, rows.size(), rows.toString());
        assertEquals("qa-fb\uFFFD", rows.getFirst().get("model"));
        assertEquals(3, ((Number) rows.getFirst().get("requests")).intValue());
        assertEquals(3, queue(file, List.of()).fallbackStatus("groq", "qa-fb\uFFFD", 1_000L).requestsToday());
    }

    @Test
    void providerKeyWithALoneSurrogateKeepsOneCountAcrossRestarts() throws Exception {
        Path file = Files.createTempDirectory("nexusai-usage-lone-provider").resolve("usage.yml");
        String provider = "gr" + HALF + "oq";
        for (int run = 0; run < 2; run++) {
            ModelQueue queue = queue(file, List.of());
            assertTrue(queue.tryConsumeFallback(provider, "m", 1_000L));
            queue.flushAndClose();
        }
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(List.of("gr\uFFFDoq"), List.copyOf(saved.getConfigurationSection("providers").getKeys(false)));
        assertEquals(2, saved.getInt("providers.gr\uFFFDoq"));
        assertEquals(2, queue(file, List.of()).providerRequests(provider));
    }

    @Test
    void duplicateRowsFromAnOlderVersionAreSummed() throws Exception {
        Path file = Files.createTempDirectory("nexusai-usage-dup").resolve("usage.yml");
        Files.writeString(file, """
                config-version: 2
                day: "2026-10-21"
                providers:
                  groq: 4
                entries:
                - id: "0|groq|m"
                  provider: groq
                  model: m
                  requests: 1
                  rejected: 1
                - id: "0|groq|m"
                  provider: groq
                  model: m
                  requests: 2
                  rejected: 0
                fallback:
                - id: "groq|qa-fb\\ufffd"
                  provider: groq
                  model: "qa-fb\\ufffd"
                  requests: 1
                  rejected: 0
                - id: "groq|qa-fb\\ufffd"
                  provider: groq
                  model: "qa-fb\\ufffd"
                  requests: 1
                  rejected: 2
                """, StandardCharsets.UTF_8);
        ModelQueue queue = queue(file, List.of(new QueueEntryConfig("groq", "m", 0)));
        assertEquals(3, queue.requestsToday(0));
        assertEquals(1, queue.status(1_000L).getFirst().rejected());
        ModelQueue.Status fallback = queue.fallbackStatus("groq", "qa-fb" + HALF, 1_000L);
        assertEquals(2, fallback.requestsToday());
        assertEquals(2, fallback.rejected());
        assertEquals(4, queue.providerRequests("groq"));
    }

    @Test
    void tokenLedgerKeyForAFallbackModelMatchesUsageFile() {
        assertEquals("groq|qa-fb\uFFFD", ModelQueue.fallbackStorageId("groq", "qa-fb" + HALF));
        assertEquals("gr\uFFFD|m", ModelQueue.fallbackStorageId("gr" + HALF, "m"));
        assertEquals("groq|qa-fb", ModelQueue.fallbackStorageId("groq", "qa-fb"));
    }

    private static ModelQueue queue(Path file, List<QueueEntryConfig> rows) {
        return new ModelQueue(rows, 0, 60_000L, 300_000L, file.toFile(), () -> 1_000L, () -> DAY,
                ZoneId.of("UTC"), Logger.getLogger("usage-lone-" + System.nanoTime()));
    }
}
