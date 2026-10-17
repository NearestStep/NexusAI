package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.ChatCaller;
import io.github.neareststep.nexusai.ai.ChatExchange;
import io.github.neareststep.nexusai.ai.RequestGate;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.api.CacheMode;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.InvalidJsonAction;
import io.github.neareststep.nexusai.api.JsonGenerationResult;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.ResponseValidation;
import io.github.neareststep.nexusai.ai.AiDiagnostics;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.budget.TokenAccounting;
import io.github.neareststep.nexusai.budget.TokenLedger;
import io.github.neareststep.nexusai.budget.TokenLedgerStore;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.generate.GenerationRuntime;
import io.github.neareststep.nexusai.generate.GenerationService;
import io.github.neareststep.nexusai.generate.RegionTasks;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.limit.RateLimiter;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseValidationTest {

    private static final String TEMPLATE = "Invent a short fetch quest for a village blacksmith.";
    private static final JsonSchema QUEST = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,
             "required":["title","goal","reward"],
             "properties":{
               "title":{"type":"string","maxLength":40},
               "goal":{"type":"string","maxLength":200},
               "reward":{"type":"integer","minimum":1,"maximum":1000}}}
            """);
    private static final JsonSchema ACTION = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,
             "required":["action","amount"],
             "properties":{
               "action":{"type":"string"},
               "amount":{"type":"integer","minimum":1,"maximum":64}}}
            """);
    private static final String VALID = "{\"title\":\"Iron nails\",\"goal\":\"Bring nails\",\"reward\":12}";
    private static final String WRONG_TYPE = "{\"title\":\"Iron nails\",\"goal\":\"Bring nails\",\"reward\":\"12\"}";
    private static final String OUT_OF_RANGE = "{\"title\":\"Iron nails\",\"goal\":\"Bring nails\",\"reward\":5000}";
    private static final String BAD_ACTION = "{\"action\":\"explode\",\"amount\":1}";
    private static final String GOOD_ACTION = "{\"action\":\"give_item\",\"amount\":3}";

    private ExecutorService http;
    private ExecutorService scheduler;

    @AfterEach
    void tearDown() throws Exception {
        StructuredOutputSupport.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (http != null) {
            http.shutdownNow();
        }
    }

    @Test
    void wrongTypeNeverReachesAsMap() throws Exception {
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.on-invalid", "fallback"));
        box.script.next = ignored -> reply(WRONG_TYPE, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertTrue(result.asMap().isEmpty());
        assertTrue(result.json().isEmpty());
        assertEquals(NexusErrorKind.INVALID_JSON, result.meta().error().orElseThrow().kind());
        assertTrue(result.validationErrors().stream().anyMatch(line -> line.contains("$.reward")));
        assertEquals(1, box.script.calls.get());
    }

    @Test
    void outOfRangeNeverReachesAsMap() throws Exception {
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.on-invalid", "fallback"));
        box.script.next = ignored -> reply(OUT_OF_RANGE, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertTrue(result.asMap().isEmpty());
        assertTrue(result.validationErrors().stream().anyMatch(line -> line.contains("above maximum")));
    }

    @Test
    void actionOutsideAllowlistNeverReachesAsMap() throws Exception {
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.on-invalid", "fallback"));
        box.script.next = ignored -> reply(BAD_ACTION, "stop");
        GenerationRequest request = GenerationRequest.template(TEMPLATE)
                .cacheMode(CacheMode.FRESH)
                .responseValidation(ResponseValidation.builder()
                        .allowedActions("give_item", "open_shop")
                        .build())
                .build();
        JsonGenerationResult result = box.service.generateJson(box.owner, request, ACTION).get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertTrue(result.asMap().isEmpty());
        assertTrue(result.validationErrors().stream().anyMatch(line -> line.contains("allowlist")));
        assertEquals(1, box.script.calls.get());
    }

    @Test
    void schemaActionAllowlistRejectsUnknownAction() throws Exception {
        JsonSchema schema = JsonSchema.withActionAllowlist(ACTION, "action", List.of("give_item", "open_shop"));
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.on-invalid", "fallback"));
        box.script.next = ignored -> reply(BAD_ACTION, "stop");
        JsonGenerationResult result = json(box, schema);
        assertFalse(result.success());
        assertTrue(result.asMap().isEmpty());
        assertTrue(result.validationErrors().stream().anyMatch(line -> line.contains("enum")));
    }

    @Test
    void onInvalidFallbackSkipsRetry() throws Exception {
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.on-invalid", "fallback"));
        box.script.next = ignored -> reply(WRONG_TYPE, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertFalse(result.success());
        assertFalse(result.repaired());
        assertEquals(1, box.script.calls.get());
        assertEquals("...", result.meta().text());
    }

    @Test
    void disabledValidationAllowsWrongTypeThrough() throws Exception {
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.enabled", false));
        box.script.next = ignored -> reply(WRONG_TYPE, "stop");
        JsonGenerationResult result = json(box, QUEST);
        assertTrue(result.success(), result.validationErrors().toString());
        assertEquals("12", result.asMap().orElseThrow().get("reward").toString());
        assertEquals(1, box.script.calls.get());
    }

    @Test
    void allowedActionSucceeds() throws Exception {
        Box box = open(yaml -> {
        });
        box.script.next = ignored -> reply(GOOD_ACTION, "stop");
        GenerationRequest request = GenerationRequest.template(TEMPLATE)
                .cacheMode(CacheMode.FRESH)
                .responseValidation(ResponseValidation.builder()
                        .allowedActions("give_item", "open_shop")
                        .build())
                .build();
        JsonGenerationResult result = box.service.generateJson(box.owner, request, ACTION).get(5, TimeUnit.SECONDS);
        assertTrue(result.success(), result.validationErrors().toString());
        assertEquals("give_item", result.asMap().orElseThrow().get("action"));
        assertEquals(3L, result.asMap().orElseThrow().get("amount"));
    }

    @Test
    void deliverOnRegionCompletesOnOwningThread() throws Exception {
        AtomicReference<Thread> delivery = new AtomicReference<>();
        AtomicInteger scheduled = new AtomicInteger();
        RegionTasks regions = new RegionTasks() {
            @Override
            public boolean owns(Player player) {
                return false;
            }

            @Override
            public void run(Plugin plugin, Player player, Runnable body, Runnable retired) {
                scheduled.incrementAndGet();
                Thread worker = new Thread(body, "region-delivery");
                worker.setDaemon(true);
                worker.start();
                try {
                    worker.join(5_000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Box box = open(yaml -> yaml.set("plugin-api.response-validation.deliver-on-region", true), regions);
        box.script.next = ignored -> reply(VALID, "stop");
        Player player = player("Alex");
        GenerationRequest request = GenerationRequest.template(TEMPLATE)
                .cacheMode(CacheMode.FRESH)
                .player(player)
                .responseValidation(ResponseValidation.builder().deliverOnRegion(true).build())
                .build();
        JsonGenerationResult result = box.service.generateJson(box.owner, request, QUEST)
                .thenApply(value -> {
                    delivery.set(Thread.currentThread());
                    return value;
                })
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(1, scheduled.get());
        assertEquals("region-delivery", delivery.get().getName());
    }

    @Test
    void defaultDeliveryStaysOnNexusAiThread() throws Exception {
        AtomicInteger scheduled = new AtomicInteger();
        AtomicReference<Thread> delivery = new AtomicReference<>();
        RegionTasks regions = new RegionTasks() {
            @Override
            public boolean owns(Player player) {
                return false;
            }

            @Override
            public void run(Plugin plugin, Player player, Runnable body, Runnable retired) {
                scheduled.incrementAndGet();
                body.run();
            }
        };
        Box box = open(yaml -> {
        }, regions);
        box.script.next = ignored -> reply(VALID, "stop");
        GenerationRequest request = GenerationRequest.template(TEMPLATE)
                .cacheMode(CacheMode.FRESH)
                .player(player("Alex"))
                .build();
        JsonGenerationResult result = box.service.generateJson(box.owner, request, QUEST)
                .thenApply(value -> {
                    delivery.set(Thread.currentThread());
                    return value;
                })
                .get(5, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(0, scheduled.get());
        assertTrue(delivery.get().getName().startsWith("nexusai-"), delivery.get().getName());
    }

    private JsonGenerationResult json(Box box, JsonSchema schema) throws Exception {
        return box.service.generateJson(
                box.owner,
                GenerationRequest.template(TEMPLATE).cacheMode(CacheMode.FRESH).build(),
                schema).get(5, TimeUnit.SECONDS);
    }

    private Box open(Consumer<YamlConfiguration> edit) {
        return open(edit, null);
    }

    private Box open(Consumer<YamlConfiguration> edit, RegionTasks regions) {
        http = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "nexusai-validation-http");
            thread.setDaemon(true);
            return thread;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexusai-validation-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        return new Box(http, scheduler, edit, regions);
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

    private static Player player(String name) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return id;
                    }
                    if ("getName".equals(method.getName())) {
                        return name;
                    }
                    if ("isOnline".equals(method.getName())) {
                        return true;
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
            Seen seenCall = new Seen(
                    StructuredOutputSupport.responseFormat(overrides),
                    overrides.maxTokens(null),
                    List.copyOf(extra));
            this.seen.add(seenCall);
            calls.incrementAndGet();
            Object outcome = next.apply(seenCall);
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

        Box(ExecutorService http, ExecutorService scheduler, Consumer<YamlConfiguration> edit, RegionTasks regions) {
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
            PluginConfig config = new PluginConfig(yaml);
            Logger logger = Logger.getLogger("response-validation");
            TokenLedger ledger = new TokenLedger(() -> LocalDate.now(ZoneId.of("UTC")), logger);
            TokenLedgerStore store = new TokenLedgerStore(null, null, ledger, logger, null, () -> 0L);
            AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
            ModelQueue queue = new ModelQueue(
                    config.modelQueue(), 0, 60_000L, 300_000L, null, () -> 10_000L,
                    LocalDate::now, ZoneId.of("UTC"), logger);
            RoutingProvider provider = new RoutingProvider(config, queue, script, http, logger);
            provider.tokenAccounting(new TokenAccounting(store));
            AiDiagnostics diagnostics = new AiDiagnostics(logger, Duration.ofSeconds(30), config::configuredSecrets);
            RequestGate gate = RequestGate.fromConfig(new RateLimiter(1_000_000, 1_000_000), config);
            AiHttpClient client = new AiHttpClient(cache, provider, config, gate, diagnostics, logger);
            if (regions == null) {
                this.service = new GenerationService(owner, http, scheduler, logger);
            } else {
                this.service = new GenerationService(
                        owner, http, scheduler, logger, regions,
                        (player, prompt, request) -> io.github.neareststep.nexusai.generate.PlayerFacts.none(),
                        new io.github.neareststep.nexusai.generate.GenerationHooks());
            }
            service.publish(new GenerationRuntime(
                    config, cache, gate, diagnostics, provider, client, PromptCatalog.empty(), KnowledgeBase.empty(), null));
        }
    }
}
