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
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
