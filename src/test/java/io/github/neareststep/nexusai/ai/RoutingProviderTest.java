package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.budget.TokenAccounting;
import io.github.neareststep.nexusai.budget.TokenLedger;
import io.github.neareststep.nexusai.budget.TokenLedgerStore;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingProviderTest {

    @Test
    void explicitCacheTtlSurvivesTheQueue() {
        PluginConfig config = config();
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0)),
                0,
                60_000L,
                300_000L,
                null,
                () -> 10_000L,
                LocalDate::now,
                ZoneId.of("UTC"),
                Logger.getLogger("route-ttl"));
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) ->
                new ChatExchange("The harbor is quiet.", Map.of(), java.time.Duration.ofSeconds(45));
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-ttl"), () -> 10_000L);
        ModelAnswer answer = provider.answer("ping", GenerationOverrides.none(), false).join();
        assertEquals("The harbor is quiet.", answer.text());
        assertEquals(java.time.Duration.ofSeconds(45), answer.cacheTtl());

        ChatCaller trimmed = (prompt, overrides, baseUrl, apiKey, model) ->
                new ChatExchange("The harbor is quiet.", Map.of());
        RoutingProvider plain = new RoutingProvider(
                config, queue, trimmed, Executors.newSingleThreadExecutor(), Logger.getLogger("route-ttl-plain"), () -> 10_000L);
        ModelAnswer normal = plain.answer("ping", GenerationOverrides.none(), false).join();
        assertEquals(null, normal.cacheTtl());
    }

    @Test
    void rotatesKeysAndSkips401ThenFailsOverOn429() throws Exception {
        if (System.getenv("NEXUSAI_API_KEY") != null && !System.getenv("NEXUSAI_API_KEY").isBlank()) {
            return;
        }
        PluginConfig config = config();
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 0), new QueueEntryConfig("groq", "llama", 0)),
                0,
                60_000L,
                300_000L,
                null,
                () -> 10_000L,
                LocalDate::now,
                ZoneId.of("UTC"),
                Logger.getLogger("route-test"));
        List<String> seen = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            seen.add(apiKey + "@" + model);
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new AiRequestException(AiErrorKind.BAD_KEY, 401, "nope", null);
            }
            if (n == 2) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "slow", null, 5L,
                        Map.of("retry-after", List.of("5")));
            }
            return new ChatExchange("ok", Map.of("x-ratelimit-remaining-requests", List.of("0"), "x-ratelimit-reset-requests", List.of("1s")));
        };
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-test"), () -> 10_000L);
        assertEquals("ok", provider.complete("ping").join());
        assertEquals(List.of("sk-one-1111@gpt-4o-mini", "sk-two-2222@gpt-4o-mini", "sk-groq-3333@llama"), seen);
        assertTrue(queue.status(10_000L).get(1).state().startsWith("COOLDOWN"));
        assertEquals("sk-one-1111", config.provider("openai").apiKeys().getFirst());
    }

    @Test
    void exhaustedQueueDoesNotCallTheApi() {
        PluginConfig config = config();
        ModelQueue queue = new ModelQueue(
                List.of(new QueueEntryConfig("openai", "gpt-4o-mini", 1)),
                0,
                1_000L,
                1_000L,
                null,
                () -> 1_000L,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("route-empty"));
        assertTrue(queue.tryConsume(0, 1_000L));
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange("nope", Map.of());
        };
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-empty"), () -> 1_000L);
        var error = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> provider.complete("ping").join());
        assertEquals(AiErrorKind.LOCAL_LIMIT, AiErrors.classify(error));
        assertEquals(0, calls.get());
        assertTrue(error.getCause().getMessage().contains("Retry after"));
    }

    @Test
    void rateLimitThenRetryNamesTheCauseUntilCooldownEnds() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 rate limit from api.openai.com", null);
            }
            return new ChatExchange("ok", Map.of());
        };
        Harness harness = harness(List.of(entry("openai", "gpt-4o-mini", 0)), clock, http);
        AiRequestException first = failure(harness.provider(), false);
        assertEquals(AiErrorKind.RATE_LIMIT, first.kind());
        assertTrue(first.getMessage().contains("HTTP 429 rate limit"));
        assertTrue(first.getMessage().contains("Retry after 1970-01-01 00:17:40"));
        assertEquals(1, calls.get());

        AiRequestException second = failure(harness.provider(), false);
        assertEquals(AiErrorKind.RATE_LIMIT, second.kind());
        assertTrue(second.getMessage().contains("HTTP 429 rate limit"));
        assertTrue(second.getMessage().contains("Retry after 1970-01-01 00:17:40"));
        assertEquals(1, calls.get());

        clock.set(1_060_000L);
        assertEquals("ok", harness.provider().complete("ping").join());
        assertEquals(2, calls.get());
    }

    @Test
    void unauthorizedKeyFallsOverToTheNextModel() {
        AtomicInteger calls = new AtomicInteger();
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            models.add(model);
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.BAD_KEY, 401, "HTTP 401 unauthorized", null);
            }
            return new ChatExchange("ok", Map.of());
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                new AtomicLong(5_000L),
                http);
        assertEquals("ok", harness.provider().complete("ping").join());
        assertEquals(List.of("gpt-4o-mini", "gpt-4o-mini", "llama"), models);
        assertTrue(harness.queue().status(5_000L).getFirst().state().startsWith("COOLDOWN"));
        assertEquals(3, calls.get());
    }

    @Test
    void emptyReplyStopsAtTheFirstModelWithoutCooldown() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(8_000L);
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            throw new AiRequestException(AiErrorKind.EMPTY_REPLY, 200, PlayerInput.EMPTY_REPLY, null);
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                clock,
                http);
        AiRequestException error = failure(harness.provider(), false);
        assertEquals(AiErrorKind.EMPTY_REPLY, error.kind());
        assertEquals(PlayerInput.EMPTY_REPLY, error.getMessage());
        assertFalse(error.getMessage().contains("missing choices"));
        assertEquals(1, calls.get());
        for (ModelQueue.Status row : harness.queue().status(clock.get())) {
            assertEquals(0, row.rejected());
            assertFalse(row.state().startsWith("COOLDOWN"));
        }
    }

    @Test
    void markupOnlyStopsAtTheFirstModelWithoutPause() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(8_000L);
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            throw new AiRequestException(AiErrorKind.MARKUP_ONLY, 200, PlayerInput.MARKUP_ONLY, null);
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                clock,
                http);
        AiRequestException error = failure(harness.provider(), false);
        assertEquals(AiErrorKind.MARKUP_ONLY, error.kind());
        assertEquals(PlayerInput.MARKUP_ONLY, error.getMessage());
        assertEquals(1, calls.get());
        for (ModelQueue.Status row : harness.queue().status(clock.get())) {
            assertEquals(0, row.rejected());
            assertFalse(row.state().startsWith("COOLDOWN"));
        }
    }

    @Test
    void contentRejectionTriesTheNextEntryWithoutCooldown() {
        AtomicLong clock = new AtomicLong(8_000L);
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            models.add(model);
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(
                        AiErrorKind.REJECTED,
                        200,
                        "The model restated the player-input guard instead of answering.",
                        null);
            }
            return new ChatExchange("ok", Map.of());
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                clock,
                http);
        assertEquals("ok", harness.provider().complete("ping").join());
        assertEquals(List.of("gpt-4o-mini", "llama"), models);
        ModelQueue.Status first = harness.queue().status(clock.get()).getFirst();
        assertEquals(1, first.rejected());
        assertEquals("ACTIVE", first.state());
        assertEquals(0, harness.queue().status(clock.get()).get(1).rejected());
    }

    @Test
    void everyContentRejectionStaysSelectable() {
        AtomicLong clock = new AtomicLong(8_000L);
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            throw new AiRequestException(
                    AiErrorKind.REJECTED,
                    200,
                    "The model restated the player-input guard instead of answering.",
                    null);
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                clock,
                http);
        AiRequestException error = failure(harness.provider(), false);
        assertEquals(AiErrorKind.REJECTED, error.kind());
        assertFalse(error.getMessage().contains("Retry after"));
        for (ModelQueue.Status row : harness.queue().status(clock.get())) {
            assertEquals(1, row.rejected());
            assertFalse(row.state().startsWith("COOLDOWN"));
        }
        assertEquals(2, harness.queue().selectable(clock.get()).size());
    }

    @Test
    void serverErrorAndTimeoutFallOverToTheNextModel() {
        assertEquals("ok", failover(new AiRequestException(AiErrorKind.OTHER, 500, "HTTP 500 from api.openai.com: down", null)));
        assertEquals("ok", failover(new AiRequestException(AiErrorKind.TIMEOUT, 0, "Request timed out calling api.openai.com", null)));
    }

    @Test
    void allFailedEntriesNameTheLastCauseAndRetryTime() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        List<String> models = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            models.add(model);
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.OTHER, 500, "HTTP 500 from api.openai.com: down", null);
            }
            throw new AiRequestException(AiErrorKind.TIMEOUT, 0, "Request timed out calling api.groq.com", null);
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                clock,
                http);
        AiRequestException error = failure(harness.provider(), false);
        assertEquals(AiErrorKind.TIMEOUT, error.kind());
        assertTrue(error.getMessage().contains("Request timed out"));
        assertTrue(error.getMessage().contains("Retry after 1970-01-01 00:17:40"));
        assertEquals(List.of("gpt-4o-mini", "llama"), models);
    }

    @Test
    void entryRecoversAfterCooldown() {
        AtomicLong clock = new AtomicLong(2_000_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiRequestException(AiErrorKind.OTHER, 500, "HTTP 500 from api.openai.com: down", null);
            }
            return new ChatExchange("back", Map.of());
        };
        Harness harness = harness(List.of(entry("openai", "gpt-4o-mini", 0)), clock, http);
        AiRequestException error = failure(harness.provider(), false);
        assertTrue(error.getMessage().contains("HTTP 500"));
        assertTrue(error.getMessage().contains("Retry after"));
        assertEquals(1, calls.get());

        clock.set(2_030_000L);
        AiRequestException still = failure(harness.provider(), false);
        assertEquals(AiErrorKind.OTHER, still.kind());
        assertEquals(1, calls.get());

        clock.set(2_060_000L);
        assertEquals("back", harness.provider().complete("ping").join());
        assertEquals(2, calls.get());
    }

    @Test
    void probeReachesHttpDuringCooldownWithoutExtendingIt() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            int n = calls.incrementAndGet();
            if (n == 1 || n == 3) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429 rate limit from api.openai.com", null);
            }
            return new ChatExchange("pong", Map.of("x-ratelimit-remaining-requests", List.of("0")));
        };
        Harness harness = harness(List.of(entry("openai", "gpt-4o-mini", 0)), clock, http);
        failure(harness.provider(), false);
        String cooled = harness.queue().status(clock.get()).getFirst().state();
        assertTrue(cooled.startsWith("COOLDOWN until "));

        assertEquals("pong", harness.provider().complete("ping", GenerationOverrides.none(), true).join());
        assertEquals(cooled, harness.queue().status(clock.get()).getFirst().state());

        AiRequestException probeError = failure(harness.provider(), true);
        assertEquals(AiErrorKind.RATE_LIMIT, probeError.kind());
        assertTrue(probeError.getMessage().contains("HTTP 429 rate limit"));
        assertEquals(cooled, harness.queue().status(clock.get()).getFirst().state());
        assertEquals(3, calls.get());
    }

    @Test
    void probeStillStopsAtTheDailyCap() {
        AtomicLong clock = new AtomicLong(1_000L);
        ModelQueue queue = new ModelQueue(
                List.of(entry("openai", "gpt-4o-mini", 1)),
                0,
                60_000L,
                300_000L,
                null,
                clock::get,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("route-probe-cap"));
        assertTrue(queue.tryConsume(0, clock.get()));
        AtomicInteger calls = new AtomicInteger();
        RoutingProvider provider = new RoutingProvider(
                config(),
                queue,
                (prompt, overrides, baseUrl, apiKey, model) -> {
                    calls.incrementAndGet();
                    return new ChatExchange("nope", Map.of());
                },
                Executors.newSingleThreadExecutor(),
                Logger.getLogger("route-probe-cap"),
                clock::get);
        AiRequestException error = failure(provider, true);
        assertEquals(AiErrorKind.LOCAL_LIMIT, error.kind());
        assertTrue(error.getMessage().contains("exhausted"));
        assertTrue(error.getMessage().contains("Retry after 2026-01-02 00:00:00"));
        assertEquals(0, calls.get());
    }

    private static String failover(AiRequestException firstError) {
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            if (calls.incrementAndGet() == 1) {
                throw firstError;
            }
            return new ChatExchange("ok", Map.of());
        };
        Harness harness = harness(
                List.of(entry("openai", "gpt-4o-mini", 0), entry("groq", "llama", 0)),
                new AtomicLong(8_000L),
                http);
        String answer = harness.provider().complete("ping").join();
        assertEquals(2, calls.get());
        assertTrue(harness.queue().status(8_000L).getFirst().state().startsWith("COOLDOWN"));
        return answer;
    }

    @Test
    void reportedUsageLandsOnThePlaceholderSlices() throws Exception {
        UUID player = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicLong clock = new AtomicLong(1_000L);
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> new ChatExchange(
                "pong",
                Map.of(),
                null,
                "",
                model,
                ResponseUsage.reported(100, 20, 120, null),
                "stop",
                1,
                false,
                1L);
        Counted counted = counted(List.of(entry("openai", "gpt-4o-mini", 0)), clock, http);
        try {
            counted.provider().answer(
                    "ping",
                    GenerationOverrides.none(),
                    false,
                    CallTrace.start(RequestOrigin.PLACEHOLDER, player, "greet", "")).get();
            TokenLedger.Snapshot snap = counted.ledger().snapshot();
            assertEquals(1L, snap.server().requests());
            assertEquals(100L, snap.server().prompt());
            assertEquals(20L, snap.server().completion());
            assertEquals(120L, snap.server().total());
            assertEquals(0L, snap.server().estimated());
            assertEquals(120L, snap.providers().get("openai").total());
            assertEquals(120L, snap.rows().get("0|openai|gpt-4o-mini").total());
            assertEquals(120L, snap.origins().get("placeholder").total());
            assertEquals(120L, snap.consumers().get("nexusai").total());
            assertEquals(120L, snap.players().get(player.toString()).total());
        } finally {
            counted.executor().shutdownNow();
        }
    }

    @Test
    void aRejectedAttemptIsCountedAndAReplyWithoutUsageIsNot() throws Exception {
        UUID player = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicLong clock = new AtomicLong(1_000L);
        ChatCaller rejected = (prompt, overrides, baseUrl, apiKey, model) -> {
            throw new AiRequestException(AiErrorKind.REJECTED, 200, "no", null)
                    .withUsage(ResponseUsage.reported(10, 2, 12, null));
        };
        Counted rejectedCall = counted(List.of(entry("openai", "gpt-4o-mini", 0)), clock, rejected);
        try {
            assertThrows(ExecutionException.class, () -> rejectedCall.provider().answer(
                    "ping",
                    GenerationOverrides.none(),
                    false,
                    CallTrace.start(RequestOrigin.TEST, player, "probe", "")).get());
            TokenLedger.Snapshot snap = rejectedCall.ledger().snapshot();
            assertEquals(1L, snap.server().requests());
            assertEquals(12L, snap.server().total());
            assertTrue(snap.players().isEmpty());
            assertEquals(12L, snap.origins().get("test").total());
        } finally {
            rejectedCall.executor().shutdownNow();
        }

        ChatCaller empty = (prompt, overrides, baseUrl, apiKey, model) -> new ChatExchange("pong", Map.of());
        Counted emptyCall = counted(List.of(entry("openai", "gpt-4o-mini", 0)), clock, empty);
        try {
            emptyCall.provider().answer(
                    "ping",
                    GenerationOverrides.none(),
                    false,
                    CallTrace.start(RequestOrigin.PLACEHOLDER, player, "greet", "")).get();
            assertEquals(0L, emptyCall.ledger().snapshot().server().requests());
        } finally {
            emptyCall.executor().shutdownNow();
        }
    }

    @Test
    void aDedicatedFallbackIsNotStoredAsAQueueRow() throws Exception {
        UUID player = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicLong clock = new AtomicLong(1_000L);
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.RATE_LIMIT, 429, "HTTP 429", null);
            }
            return new ChatExchange(
                    "pong", Map.of(), null, "", model, ResponseUsage.reported(4, 1, 5, null), "stop", 1, false, 1L);
        };
        Counted counted = counted(List.of(entry("openai", "gpt-4o-mini", 0)), clock, http);
        try {
            counted.provider().answer(
                    "ping",
                    GenerationOverrides.none().withFallbackModel("groq", "llama"),
                    false,
                    CallTrace.start(RequestOrigin.TALK, player, "npc", "")).get();
            TokenLedger.Snapshot snap = counted.ledger().snapshot();
            assertEquals(1L, snap.server().requests());
            assertEquals(5L, snap.server().total());
            assertEquals(5L, snap.fallback().get("groq|llama").total());
            assertTrue(snap.rows().isEmpty());
            assertEquals(5L, snap.players().get(player.toString()).total());
            assertEquals(5L, snap.origins().get("talk").total());
        } finally {
            counted.executor().shutdownNow();
        }
    }

    private static AiRequestException failure(RoutingProvider provider, boolean probe) {
        CompletionException error = assertThrows(CompletionException.class,
                () -> provider.complete("ping", GenerationOverrides.none(), probe).join());
        AiRequestException typed = AiErrors.find(error);
        assertNotNull(typed);
        return typed;
    }

    private static QueueEntryConfig entry(String provider, String model, int limit) {
        return new QueueEntryConfig(provider, model, limit);
    }

    private static Harness harness(List<QueueEntryConfig> entries, AtomicLong clock, ChatCaller http) {
        ModelQueue queue = new ModelQueue(
                entries,
                0,
                60_000L,
                300_000L,
                null,
                clock::get,
                () -> LocalDate.of(2026, 1, 1),
                ZoneId.of("UTC"),
                Logger.getLogger("route-harness"));
        RoutingProvider provider = new RoutingProvider(
                config(), queue, http, Executors.newSingleThreadExecutor(), Logger.getLogger("route-harness"), clock::get);
        return new Harness(queue, provider);
    }

    private record Harness(ModelQueue queue, RoutingProvider provider) {
    }

    private static Counted counted(List<QueueEntryConfig> entries, AtomicLong clock, ChatCaller http) {
        ModelQueue queue = new ModelQueue(
                entries,
                0,
                60_000L,
                300_000L,
                null,
                clock::get,
                () -> LocalDate.of(2026, 10, 7),
                ZoneId.of("UTC"),
                Logger.getLogger("route-tokens"));
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "route-tokens");
            thread.setDaemon(true);
            return thread;
        });
        RoutingProvider provider = new RoutingProvider(
                config(), queue, http, executor, Logger.getLogger("route-tokens"), clock::get);
        TokenLedger ledger = new TokenLedger(() -> LocalDate.of(2026, 10, 7), Logger.getLogger("route-tokens"));
        provider.tokenAccounting(new TokenAccounting(new TokenLedgerStore(
                null,
                null,
                ledger,
                Logger.getLogger("route-tokens"),
                () -> OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, ZoneOffset.UTC),
                clock::get)));
        return new Counted(provider, ledger, executor);
    }

    private record Counted(RoutingProvider provider, TokenLedger ledger, ExecutorService executor) {
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.auth-pause-seconds", 300);
        yaml.createSection("providers.openai");
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", java.util.List.of("sk-one-1111", "sk-two-2222"));
        yaml.createSection("providers.groq");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://api.groq.com/openai/v1");
        yaml.set("providers.groq.api-key", "sk-groq-3333");
        yaml.set("model-queue", java.util.List.of(
                java.util.Map.of("provider", "openai", "model", "gpt-4o-mini"),
                java.util.Map.of("provider", "groq", "model", "llama")
        ));
        return new PluginConfig(yaml);
    }
}
