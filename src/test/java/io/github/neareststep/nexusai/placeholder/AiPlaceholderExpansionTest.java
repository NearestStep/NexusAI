package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusContextProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.CachedContextCoordinator;
import io.github.neareststep.nexusai.context.ContextRegistry;
import io.github.neareststep.nexusai.context.ContextService;
import io.github.neareststep.nexusai.context.ContextSnapshots;
import io.github.neareststep.nexusai.context.RegionOwnership;
import io.github.neareststep.nexusai.context.RegionPlayerFixture;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.pool.AiPool;
import io.github.neareststep.nexusai.pool.PoolService;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlaceholderExpansionTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;
    private ContextService contexts;
    private RecordingProvider provider;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nexusai-scheduler-test");
            thread.setDaemon(true);
            return thread;
        });
        workers = ContextService.newWorkerPool(1, 8);
        Logger logger = Logger.getLogger("placeholder-region");
        ContextRegistry registry = new ContextRegistry(logger);
        provider = new RecordingProvider();
        registry.add("probe", provider);
        contexts = new ContextService(registry, null, workers, scheduler, logger, System::currentTimeMillis, 30);
    }

    @AfterEach
    void tearDown() {
        RegionOwnership.reset();
        VarSubstitutor.resetLookup();
        if (workers != null) {
            workers.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void cachedPlaceholderOffRegionDoesNotThrowAndPassesAnEmptyWorld() throws Exception {
        AtomicBoolean touched = new AtomicBoolean();
        RegionOwnership.install(player -> false);
        Player player = offRegion(touched);
        String answer = expansion().onPlaceholderRequest(player, "cached_shop");
        assertEquals("...", answer);
        assertFalse(touched.get());
        assertTrue(provider.seen.await(5, TimeUnit.SECONDS), "context provider was not called");
        ContextRequest request = provider.request.get();
        assertNotNull(request);
        assertEquals("", request.world());
        assertEquals("shop", request.promptId());
        assertEquals(ContextRequest.Purpose.PLACEHOLDER, request.purpose());
        assertEquals("Steve", request.playerName());
    }

    @Test
    void cachedPlaceholderOnTheOwningRegionPassesTheWorld() throws Exception {
        RegionOwnership.install(player -> true);
        Player player = RegionPlayerFixture.named("Steve", "lobby");
        expansion().onPlaceholderRequest(player, "cached_shop");
        assertTrue(provider.seen.await(5, TimeUnit.SECONDS), "context provider was not called");
        assertEquals("lobby", provider.request.get().world());
    }

    @Test
    void backgroundPlaceholderResolvesHealthAfterTheOwnerHop() throws Exception {
        AtomicBoolean inHop = new AtomicBoolean();
        AtomicInteger hops = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicBoolean lookupOffHop = new AtomicBoolean();
        AtomicReference<String> sent = new AtomicReference<>();
        VarSubstitutor.installLookup((player, template) -> {
            lookups.incrementAndGet();
            if (!inHop.get()) {
                lookupOffHop.set(true);
            }
            return "%player_health%".equals(template) ? "20" : "";
        });
        RegionOwnership.install(player -> inHop.get());
        Player player = RegionPlayerFixture.named("Steve", "lobby", entityScheduler(inHop, hops));
        AiPlaceholderExpansion expansion = expansion(chatConfig(), sent, null);
        String shown = expansion.onPlaceholderRequest(player, "cached_vitals");
        assertEquals("...", shown);
        assertEquals(1, hops.get());
        assertEquals(1, lookups.get());
        assertFalse(lookupOffHop.get());
        assertNotNull(sent.get());
        assertTrue(sent.get().contains("20"), sent.get());
        assertFalse(sent.get().contains("%player_health%"), sent.get());
    }

    @Test
    void poolRefillResolvesHealthAfterTheOwnerHop() throws Exception {
        AtomicBoolean inHop = new AtomicBoolean();
        AtomicInteger hops = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicBoolean lookupOffHop = new AtomicBoolean();
        AtomicReference<String> sent = new AtomicReference<>();
        VarSubstitutor.installLookup((player, template) -> {
            lookups.incrementAndGet();
            if (!inHop.get()) {
                lookupOffHop.set(true);
            }
            return "%player_health%".equals(template) ? "20" : "";
        });
        RegionOwnership.install(player -> inHop.get());
        Player player = RegionPlayerFixture.named("Steve", "lobby", entityScheduler(inHop, hops));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("limits.max-prompt-length", 400);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("pool.enabled", true);
        yaml.set("pool.entries", List.of(Map.of(
                "prompt", "vitals",
                "size", 1,
                "min-threshold", 1,
                "vars", Map.of("hp", "%player_health%"))));
        yaml.set("prewarm.enabled", false);
        PluginConfig config = new PluginConfig(yaml);
        Logger logger = Logger.getLogger("placeholder-pool-hop");
        AtomicReference<String> recorded = sent;
        AiHttpClient http = new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 10),
                prompt -> {
                    recorded.set(prompt);
                    return CompletableFuture.completedFuture("pong");
                },
                config,
                logger);
        PromptCatalog catalog = vitalsCatalog();
        PoolService pools = new PoolService(
                config, new AiPool(), http, logger, null, null, catalog, KnowledgeBase.empty());
        pools.start();
        AiPlaceholderExpansion expansion = new AiPlaceholderExpansion(
                schedulerPlugin(),
                config,
                new AiCache(Duration.ofMinutes(5), 10),
                http,
                new AiPool(),
                pools,
                catalog,
                KnowledgeBase.empty(),
                null,
                null,
                logger);
        String shown = expansion.onPlaceholderRequest(player, "generate_vitals");
        assertEquals("...", shown);
        assertEquals(1, hops.get());
        assertEquals(1, lookups.get());
        assertFalse(lookupOffHop.get());
        assertNotNull(sent.get());
        assertTrue(sent.get().contains("20"), sent.get());
        assertFalse(sent.get().contains("%player_health%"), sent.get());
        pools.shutdown();
    }

    @Test
    void promptAfterHopKeepsTheContextSuffix() {
        String original = "Health is .";
        String sent = original + "\n\nPlayer context:\nok";
        assertEquals(
                "Health is 20.\n\nPlayer context:\nok",
                AiPlaceholderExpansion.promptAfterHop(original, sent, "Health is 20."));
    }

    private AiPlaceholderExpansion expansion() {
        PluginConfig config = new PluginConfig(new YamlConfiguration());
        Logger logger = Logger.getLogger("placeholder-region-http");
        AiHttpClient http = new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 10),
                prompt -> CompletableFuture.completedFuture("pong"),
                config,
                logger);
        CachedContextCoordinator coordinator = new CachedContextCoordinator(
                new ContextSnapshots(), System::currentTimeMillis, () -> Duration.ofSeconds(30));
        return new AiPlaceholderExpansion(
                config,
                new AiCache(Duration.ofMinutes(5), 10),
                http,
                new AiPool(),
                null,
                PromptCatalog.parse("""
                        shop:
                          prompt: "Give one tip."
                          fallback: "..."
                          context:
                            - smoke
                        """).catalog(),
                KnowledgeBase.empty(),
                coordinator,
                contexts,
                logger);
    }

    private AiPlaceholderExpansion expansion(PluginConfig config, AtomicReference<String> sent, PoolService pools) {
        Logger logger = Logger.getLogger("placeholder-region-http");
        AiHttpClient http = new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 10),
                prompt -> {
                    if (sent != null) {
                        sent.set(prompt);
                    }
                    return CompletableFuture.completedFuture("pong");
                },
                config,
                logger);
        return new AiPlaceholderExpansion(
                schedulerPlugin(),
                config,
                new AiCache(Duration.ofMinutes(5), 10),
                http,
                new AiPool(),
                pools,
                vitalsCatalog(),
                KnowledgeBase.empty(),
                null,
                null,
                logger);
    }

    private static PluginConfig chatConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("limits.max-prompt-length", 400);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("pool.enabled", false);
        yaml.set("prewarm.enabled", false);
        yaml.set("fallback", "...");
        return new PluginConfig(yaml);
    }

    private static PromptCatalog vitalsCatalog() {
        return PromptCatalog.parse("""
                vitals:
                  prompt: "Health is {hp}."
                  fallback: "..."
                  vars:
                    hp: "%player_health%"
                """).catalog();
    }

    private static Plugin schedulerPlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return "NexusAI";
                    }
                    if ("isEnabled".equals(method.getName())) {
                        return true;
                    }
                    if ("getLogger".equals(method.getName())) {
                        return Logger.getLogger("placeholder-hop");
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

    private static EntityScheduler entityScheduler(AtomicBoolean inHop, AtomicInteger hops) {
        return (EntityScheduler) java.lang.reflect.Proxy.newProxyInstance(
                EntityScheduler.class.getClassLoader(),
                new Class<?>[]{EntityScheduler.class},
                (proxy, method, args) -> {
                    if ("run".equals(method.getName()) && args != null && args.length >= 2
                            && args[1] instanceof Consumer<?> consumer) {
                        hops.incrementAndGet();
                        inHop.set(true);
                        try {
                            consumer.accept(null);
                        } finally {
                            inHop.set(false);
                        }
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
    }

    private static Player offRegion(AtomicBoolean touched) {
        UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Steve";
                    case "toString" -> "Steve";
                    default -> {
                        touched.set(true);
                        throw new AssertionError("player state read: " + method.getName());
                    }
                });
    }

    private static final class RecordingProvider implements NexusContextProvider {
        private final AtomicReference<ContextRequest> request = new AtomicReference<>();
        private final CountDownLatch seen = new CountDownLatch(1);

        @Override
        public String id() {
            return "smoke";
        }

        @Override
        public CompletableFuture<String> provide(ContextRequest incoming) {
            request.set(incoming);
            seen.countDown();
            return CompletableFuture.completedFuture("ok");
        }
    }
}
