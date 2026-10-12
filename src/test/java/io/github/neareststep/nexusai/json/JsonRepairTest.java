package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.ChatCaller;
import io.github.neareststep.nexusai.ai.ChatExchange;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.api.CacheMode;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.JsonGenerationResult;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.PromptDefinition;
import io.github.neareststep.nexusai.api.ResultSource;
import io.github.neareststep.nexusai.api.StructuredMode;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.budget.QuotaPolicy;
import io.github.neareststep.nexusai.budget.QuotaSettings;
import io.github.neareststep.nexusai.budget.TokenAccounting;
import io.github.neareststep.nexusai.budget.TokenLedger;
import io.github.neareststep.nexusai.budget.TokenLedgerStore;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.event.EventDispatcher;
import io.github.neareststep.nexusai.event.EventSupport;
import io.github.neareststep.nexusai.generate.GenerationRuntime;
import io.github.neareststep.nexusai.generate.GenerationService;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRepairTest {

    private static final String VALID = "{\"title\":\"Iron nails\",\"goal\":\"Bring ten iron nails.\",\"reward\":12}";
    private static final String INVALID = "{\"title\":\"Iron nails\",\"goal\":\"Bring ten iron nails.\",\"reward\":\"nope\"}";
    private static final String TEMPLATE = "Invent a short fetch quest for a village blacksmith.";
    private static final JsonSchema QUEST = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,"required":["title","goal","reward"],
             "properties":{"title":{"type":"string","maxLength":40},"goal":{"type":"string","maxLength":200},
             "reward":{"type":"integer","minimum":1,"maximum":1000}}}
            """);

    private ExecutorService http;
    private ExecutorService scheduler;
    private EventSupport support;

    @AfterEach
    void tearDown() {
        StructuredOutputSupport.clear();
        NexusAIApi.bindGeneration(null);
        if (support != null) {
            support.close();
            support = null;
        }
        EventDispatcher.install(null);
        if (http != null) {
            http.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void validJsonSucceedsWithTheSchemaTypes() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = ignored -> reply(VALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success());
        assertEquals(StructuredMode.JSON_SCHEMA, result.mode());
        assertEquals(VALID, result.json().orElseThrow());
        assertEquals(VALID, result.meta().text());
        assertEquals("Iron nails", result.asMap().orElseThrow().get("title"));
        assertEquals(12L, result.asMap().orElseThrow().get("reward"));
        assertEquals(1, result.meta().attempts());
        assertFalse(result.repaired());
        assertEquals("json_schema", formatType(box.script.seen.get(0)));
        assertEquals(Boolean.TRUE, schemaStrict(box.script.seen.get(0)));
        assertEquals(1024, box.script.seen.get(0).maxTokens());
    }

    @Test
    void aRefusalStepsToJsonObjectAndReloadTriesTheSchemaAgain() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = seen -> "json_schema".equals(formatType(seen))
                ? new AiRequestException(AiErrorKind.OTHER, 400, "response_format is not supported", null)
                : reply(VALID, "stop");
        GenerationRequest fresh = GenerationRequest.template(TEMPLATE).cacheMode(CacheMode.FRESH).build();
        JsonGenerationResult first = box.service.generateJson(box.owner, fresh, QUEST).get(5, TimeUnit.SECONDS);
        assertTrue(first.success());
        assertEquals(StructuredMode.JSON_OBJECT, first.mode());
        assertEquals(2, box.script.calls.get());
        assertEquals(List.of("json_schema", "json_object"), types(box.script));

        JsonGenerationResult second = box.service.generateJson(box.owner, fresh, QUEST).get(5, TimeUnit.SECONDS);
        assertTrue(second.success());
        assertEquals(StructuredMode.JSON_OBJECT, second.mode());
        assertEquals(3, box.script.calls.get());

        StructuredOutputSupport.clear();
        JsonGenerationResult third = box.service.generateJson(box.owner, fresh, QUEST).get(5, TimeUnit.SECONDS);
        assertTrue(third.success());
        assertEquals(StructuredMode.JSON_OBJECT, third.mode());
        assertEquals(5, box.script.calls.get());
    }

    @Test
    void aFixedModeDoesNotStepDown() throws Exception {
        Box box = open(yaml -> yaml.set("providers.openai.structured-output", "json-schema"));
        box.script.next = ignored -> new AiRequestException(AiErrorKind.OTHER, 400, "response_format is not supported", null);
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertEquals(NexusErrorKind.PROVIDER_ERROR, result.meta().error().orElseThrow().kind());
        assertFalse(result.repaired());
        assertEquals(1, box.script.calls.get());
    }

    @Test
    void fencedAndSurroundingTextAreExtracted() throws Exception {
        Box fenced = open(yaml -> {
        });
        fenced.script.next = ignored -> reply("```json\n" + VALID + "\n```", "stop");
        JsonGenerationResult fence = json(fenced, QUEST);
        assertTrue(fence.success());
        assertEquals(VALID, fence.json().orElseThrow());

        Box prose = open(yaml -> {
        });
        prose.script.next = ignored -> reply("Here you go " + VALID + " thanks", "stop");
        JsonGenerationResult around = json(prose, QUEST);
        assertTrue(around.success());
        assertEquals(VALID, around.json().orElseThrow());
    }

    @Test
    void oneInvalidReplyIsRepairedAndBothUsagesAreCounted() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = seen -> box.script.calls.get() == 1 ? reply(INVALID, "stop") : reply(VALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success());
        assertTrue(result.repaired());
        assertEquals(2, result.meta().attempts());
        assertEquals(12, result.meta().usage().totalTokens());
        assertEquals(12L, box.ledger.snapshot().server().total());
        assertEquals(2, box.script.calls.get());
        assertTrue(box.script.seen.get(1).extra().stream().anyMatch(line -> line.contains(JsonRepair.USER_PREFIX)));
        assertTrue(box.script.seen.get(1).extra().stream().anyMatch(line -> line.contains("$.reward: expected integer, got string")));
    }

    @Test
    void twoInvalidRepliesReturnInvalidJsonWithoutBackoff() throws Exception {
        Box box = open(yaml -> yaml.set("limits.error-backoff-initial-seconds", 60));
        box.script.next = ignored -> reply(INVALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertTrue(result.repaired());
        assertEquals(NexusErrorKind.INVALID_JSON, result.meta().error().orElseThrow().kind());
        assertEquals("...", result.meta().text());
        assertTrue(result.json().isEmpty());
        assertTrue(result.validationErrors().stream().anyMatch(line -> line.contains("$.reward")));
        assertEquals(2, box.script.calls.get());
        assertEquals(0L, box.gate.failureEpoch(admission(TEMPLATE)));
        assertEquals(0L, box.gate.blockedForMillis(admission(TEMPLATE)));
    }

    @Test
    void lengthDoublesMaxTokensAndOmitsTheCutReply() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = seen -> box.script.calls.get() == 1
                ? reply("{\"title\":\"Iron", "length")
                : reply(VALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success());
        assertTrue(result.repaired());
        assertEquals(1024, box.script.seen.get(0).maxTokens());
        assertEquals(2048, box.script.seen.get(1).maxTokens());
        assertTrue(box.script.seen.get(1).extra().isEmpty());
    }

    @Test
    void stringValuesAreStrippedAndMasked() throws Exception {
        String canary = "questcanarysecretvalue";
        JsonSchema wide = JsonSchema.parse("""
                {"type":"object","additionalProperties":false,"required":["title","goal","reward"],
                 "properties":{"title":{"type":"string","maxLength":80},"goal":{"type":"string","maxLength":200},
                 "reward":{"type":"integer","minimum":1,"maximum":1000}}}
                """);
        Box box = open(yaml -> yaml.set("providers.openai.api-key", List.of("sk-one-1111", canary)));
        String dirty = "{\"title\":\"§cHi <click:run_command:/op a> " + canary
                + "\",\"goal\":\"Bring nails\",\"reward\":1}";
        box.script.next = ignored -> reply(dirty, "stop");
        JsonGenerationResult result = json(box, wide);
        assertTrue(result.success(), result.validationErrors().toString());
        String json = result.json().orElseThrow();
        assertFalse(json.contains("§"));
        assertFalse(json.contains("<click"));
        assertFalse(json.contains(canary));
        assertTrue(json.contains("****"));
    }

    @Test
    void cacheKeepsOneSchemaAndDoesNotServeText() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = ignored -> reply(VALID, "stop");
        JsonSchema other = JsonSchema.parse("""
                {"type":"object","description":"other","additionalProperties":false,
                 "required":["title","goal","reward"],
                 "properties":{"title":{"type":"string","maxLength":40},"goal":{"type":"string","maxLength":200},
                 "reward":{"type":"integer","minimum":1,"maximum":1000}}}
                """);
        assertEquals(ResultSource.MODEL, json(box, QUEST).meta().source());
        assertEquals(ResultSource.CACHE, json(box, QUEST).meta().source());
        assertEquals(1, box.script.calls.get());
        JsonGenerationResult different = json(box, other);
        assertEquals(ResultSource.MODEL, different.meta().source());
        assertEquals(2, box.script.calls.get());
        assertEquals(ResultSource.MODEL, box.service.generate(
                box.owner, GenerationRequest.template(TEMPLATE).build()).get(5, TimeUnit.SECONDS).source());
        assertEquals(3, box.script.calls.get());
    }

    @Test
    void formatDoesNotSplitTheJsonCache() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = ignored -> reply(VALID, "stop");
        GenerationRequest chat = GenerationRequest.template(TEMPLATE).format("chat").build();
        GenerationRequest hologram = GenerationRequest.template(TEMPLATE).format("hologram").build();
        assertEquals(ResultSource.MODEL, box.service.generateJson(box.owner, chat, QUEST).get(5, TimeUnit.SECONDS).meta().source());
        assertEquals(ResultSource.CACHE, box.service.generateJson(box.owner, hologram, QUEST).get(5, TimeUnit.SECONDS).meta().source());
        assertEquals(1, box.script.calls.get());
    }

    @Test
    void aProviderErrorOnTheFirstAttemptDoesNotSpendTheRepair() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = ignored -> new AiRequestException(AiErrorKind.OTHER, 500, "server error", null);
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertFalse(result.repaired());
        assertEquals(NexusErrorKind.PROVIDER_ERROR, result.meta().error().orElseThrow().kind());
        assertEquals(1, box.script.calls.get());
        assertTrue(box.script.seen.get(0).extra().isEmpty());
    }

    @Test
    void aProviderErrorOnTheRepairIsNotInvalidJson() throws Exception {
        Box box = open(yaml -> yaml.set("limits.error-backoff-initial-seconds", 60));
        box.script.next = seen -> box.script.calls.get() == 1
                ? reply(INVALID, "stop")
                : new AiRequestException(AiErrorKind.OTHER, 500, "server error", null).withUsage(ResponseUsage.reported(1, 1, 2, null));
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertTrue(result.repaired());
        assertEquals(NexusErrorKind.PROVIDER_ERROR, result.meta().error().orElseThrow().kind());
        assertNotEqualsInvalid(result);
        assertTrue(box.gate.failureEpoch(admission(TEMPLATE)) > 0L);
        assertEquals(8, result.meta().usage().totalTokens());
    }

    @Test
    void theDailyRequestLimitIsSpentOnceForTheRepair() throws Exception {
        Box box = open(
                yaml -> {
                },
                new RateLimiter(1_000_000, 1));
        box.script.next = seen -> box.script.calls.get() == 1 ? reply(INVALID, "stop") : reply(VALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success(), String.valueOf(result.meta().error()));
        assertTrue(result.repaired());
        assertEquals(2, box.script.calls.get());
    }

    @Test
    void aQuotaEqualToTheFirstHoldStillAllowsTheRepair() throws Exception {
        Box box = open(yaml -> {
        });
        GenerationOverrides priced = StructuredOutputSupport.call(GenerationOverrides.none().withMaxTokens(1024), QUEST);
        long estimate = QuotaEstimates.forCall(box.config, TEMPLATE, priced, "gpt-4o-mini");
        QuotaPolicy policy = new QuotaPolicy(
                box.ledger, LocalDate::now, System::currentTimeMillis, Logger.getLogger("json-quota"), List::of);
        policy.apply(new QuotaSettings(true, estimate, 0L, Map.of(), Map.of()));
        box.service.quotas(policy);
        box.script.next = seen -> box.script.calls.get() == 1 ? reply(INVALID, "stop") : reply(VALID, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success(), String.valueOf(result.meta().error()));
        assertEquals(2, box.script.calls.get());
    }

    @Test
    void repairFiresOnePreAndAPostWithTwoAttempts() throws Exception {
        support = EventSupport.register("JsonOrder");
        List<Event> events = new ArrayList<>();
        Logger logger = Logger.getLogger("json-events");
        logger.setUseParentHandlers(false);
        EventDispatcher.install(EventDispatcher.create(logger, List::of, events::add, System::nanoTime, () -> false));
        Box box = open(yaml -> {
        });
        box.script.next = seen -> box.script.calls.get() == 1 ? reply(INVALID, "stop") : reply(VALID, "stop");
        assertTrue(json(box, QUEST).success());
        assertEquals(1, events.stream().filter(event -> event instanceof NexusPreGenerateEvent).count());
        assertEquals(0, events.stream().filter(event -> event instanceof NexusGenerateFailEvent).count());
        NexusPostGenerateEvent post = events.stream()
                .filter(event -> event instanceof NexusPostGenerateEvent)
                .map(event -> (NexusPostGenerateEvent) event)
                .findFirst()
                .orElseThrow();
        assertEquals(2, post.attempts());
        assertEquals(VALID, post.text());
    }

    @Test
    void theTwoArgumentCallUsesThePromptSchema() throws Exception {
        Box box = open(yaml -> {
        });
        NexusAIApi.bindGeneration(box.service);
        NexusAIApi.registerPrompt(box.owner, "quest", PromptDefinition.builder(TEMPLATE).jsonSchema(QUEST).build());
        box.script.next = ignored -> reply(VALID, "stop");
        JsonGenerationResult result = NexusAIApi.generateJson(
                box.owner, GenerationRequest.prompt("quests:quest").build()).get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(12L, result.asMap().orElseThrow().get("reward"));

        NexusAIApi.registerPrompt(box.owner, "plain", PromptDefinition.builder("Hello").build());
        JsonGenerationResult missing = NexusAIApi.generateJson(
                box.owner, GenerationRequest.prompt("quests:plain").build()).get(5, TimeUnit.SECONDS);
        assertEquals(NexusErrorKind.INVALID_REQUEST, missing.meta().error().orElseThrow().kind());
        assertEquals("prompt has no JSON schema", missing.meta().error().orElseThrow().message());

        JsonGenerationResult unknown = NexusAIApi.generateJson(
                box.owner, GenerationRequest.prompt("quests:missing").build()).get(5, TimeUnit.SECONDS);
        assertEquals(NexusErrorKind.UNKNOWN_PROMPT, unknown.meta().error().orElseThrow().kind());
        NexusAIApi.unregisterPrompt(box.owner, "quest");
        NexusAIApi.unregisterPrompt(box.owner, "plain");
    }

    @Test
    void nullArgumentsAndADisabledServiceFailBeforeHttp() {
        Plugin owner = plugin("Quests");
        GenerationRequest request = GenerationRequest.template(TEMPLATE).build();
        assertThrows(IllegalArgumentException.class, () -> NexusAIApi.generateJson(null, request, QUEST));
        assertThrows(IllegalArgumentException.class, () -> NexusAIApi.generateJson(owner, null, QUEST));
        assertThrows(IllegalArgumentException.class, () -> NexusAIApi.generateJson(owner, request, null));
        ExecutionException disabled = assertThrows(ExecutionException.class, () ->
                NexusAIApi.generateJson(owner, request, QUEST).get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, disabled.getCause());
    }

    private static void assertNotEqualsInvalid(JsonGenerationResult result) {
        assertFalse(result.meta().error().orElseThrow().kind() == NexusErrorKind.INVALID_JSON);
    }

    private JsonGenerationResult json(Box box, JsonSchema schema) throws Exception {
        return box.service.generateJson(
                box.owner, GenerationRequest.template(TEMPLATE).fallback("...").build(), schema).get(5, TimeUnit.SECONDS);
    }

    private Box open(Consumer<YamlConfiguration> edit) {
        return open(edit, new RateLimiter(1_000_000, 1_000_000));
    }

    private Box open(Consumer<YamlConfiguration> edit, RateLimiter limiter) {
        http = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "nexusai-json-http");
            thread.setDaemon(true);
            return thread;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexusai-json-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        return new Box(http, scheduler, edit, limiter);
    }

    private static String admission(String template) {
        return "api:Quests:" + KnowledgeBase.sha256(template);
    }

    private static String formatType(Seen seen) {
        if (!(seen.format() instanceof Map<?, ?> map)) {
            return "none";
        }
        Object type = map.get("type");
        return type == null ? "none" : type.toString();
    }

    private static Boolean schemaStrict(Seen seen) {
        if (!(seen.format() instanceof Map<?, ?> map)) {
            return null;
        }
        Object inner = map.get("json_schema");
        if (!(inner instanceof Map<?, ?> schema)) {
            return null;
        }
        Object strict = schema.get("strict");
        return strict instanceof Boolean flag ? flag : null;
    }

    private static List<String> types(Script script) {
        List<String> types = new ArrayList<>();
        for (Seen seen : script.seen) {
            types.add(formatType(seen));
        }
        return types;
    }

    private static ChatExchange reply(String text, String finish) {
        return new ChatExchange(
                text, Map.of(), null, "", "", ResponseUsage.reported(4, 2, 6, null), finish, 0, false, 1_000_000L);
    }

    private static Plugin plugin(String name) {
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

    private record Seen(Object format, Integer maxTokens, List<String> extra) {
    }

    private static final class Script implements ChatCaller {
        final AtomicInteger calls = new AtomicInteger();
        final List<Seen> seen = new ArrayList<>();
        volatile Function<Seen, Object> next = ignored -> reply(VALID, "stop");

        @Override
        public ChatExchange exchange(String prompt, GenerationOverrides overrides, String baseUrl, String apiKey, String model) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<ChatExchange> exchangeAsync(
                String prompt,
                GenerationOverrides overrides,
                String baseUrl,
                String apiKey,
                String model,
                io.github.neareststep.nexusai.ai.CallTrace trace
        ) {
            List<String> extra = new ArrayList<>();
            for (var message : StructuredOutputSupport.extraMessages(overrides)) {
                extra.add(message.getContent());
            }
            Seen seen = new Seen(
                    StructuredOutputSupport.responseFormat(overrides),
                    overrides.maxTokens(null),
                    List.copyOf(extra));
            this.seen.add(seen);
            calls.incrementAndGet();
            Object outcome = next.apply(seen);
            if (outcome instanceof Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
            if (outcome instanceof ChatExchange exchange) {
                return CompletableFuture.completedFuture(exchange);
            }
            return CompletableFuture.completedFuture(reply(VALID, "stop"));
        }
    }

    private static final class Box {
        final Plugin owner = plugin("Quests");
        final Script script = new Script();
        final GenerationService service;
        final RequestGate gate;
        final TokenLedger ledger;
        final PluginConfig config;

        Box(ExecutorService http, ExecutorService scheduler, Consumer<YamlConfiguration> edit, RateLimiter limiter) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("api.provider", "openai");
            yaml.set("api.model", "gpt-4o-mini");
            yaml.set("api.max-tokens", 256);
            yaml.set("api.key", "");
            yaml.set("fallback", "...");
            yaml.set("limits.provider-pause-seconds", 60);
            yaml.set("limits.auth-pause-seconds", 300);
            yaml.createSection("providers.openai");
            yaml.set("providers.openai.type", "openai-compatible");
            yaml.set("providers.openai.url", "https://api.openai.com/v1");
            yaml.set("providers.openai.api-key", List.of("sk-one-1111", "sk-two-2222"));
            yaml.set("model-queue", List.of(Map.of("provider", "openai", "model", "gpt-4o-mini")));
            edit.accept(yaml);
            this.config = new PluginConfig(yaml);
            Logger logger = Logger.getLogger("json-repair");
            this.ledger = new TokenLedger(() -> LocalDate.now(ZoneId.of("UTC")), logger);
            TokenLedgerStore store = new TokenLedgerStore(null, null, ledger, logger, null, () -> 0L);
            AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
            ModelQueue queue = new ModelQueue(
                    config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                    LocalDate::now, ZoneId.of("UTC"), logger);
            RoutingProvider provider = new RoutingProvider(config, queue, script, http, logger);
            provider.tokenAccounting(new TokenAccounting(store));
            AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), config::configuredSecrets);
            this.gate = RequestGate.fromConfig(limiter, config);
            AiHttpClient client = new AiHttpClient(cache, provider, config, gate, diagnostics, logger);
            this.service = new GenerationService(owner, http, scheduler, logger);
            service.publish(new GenerationRuntime(
                    config, cache, gate, diagnostics, provider, client, PromptCatalog.empty(), KnowledgeBase.empty(), null));
        }
    }
}
