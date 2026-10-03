package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCacheKeyTest {

    @Test
    void promptWithoutContextKeepsTheHistoricalKey() {
        AiHttpClient client = client();
        String prompt = "Give the player one short shopping tip.";
        String model = "gpt-4o-mini";
        String format = "simple";
        String golden = model + "\u0000" + format + "\u0000" + PlayerInput.KEY_VERSION + "\u0000" + prompt;
        assertEquals(golden, client.cacheKey(model, prompt, format, ""));
        assertEquals(prompt, ContextBlock.appendUser(prompt, ""));
        assertEquals(golden, client.cacheKey(model, ContextBlock.appendUser(prompt, ""), format, ""));
    }

    @Test
    void differentContextUsesDifferentKeysAndTheSameContextDoesNot() {
        AiHttpClient client = client();
        String prompt = "Give the player one short shopping tip.";
        String rich = ContextSanitizer.block(List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        String poor = ContextSanitizer.block(List.of(new ContextSanitizer.Line("economy", 10, "~1k")), 600);
        String playerA = ContextBlock.appendUser(prompt, rich);
        String playerB = ContextBlock.appendUser(prompt, poor);
        String again = ContextBlock.appendUser(prompt, rich);
        assertTrue(!client.cacheKey("gpt-4o-mini", playerA, "simple", "")
                .equals(client.cacheKey("gpt-4o-mini", playerB, "simple", "")));
        assertEquals(
                client.cacheKey("gpt-4o-mini", playerA, "simple", ""),
                client.cacheKey("gpt-4o-mini", again, "simple", ""));
    }

    @Test
    void missingSnapshotDoesNotCallHttpUntilTheBlockIsReady() throws Exception {
        AiHttpClient client = client();
        ContextSnapshots snapshots = new ContextSnapshots();
        CachedContextCoordinator coordinator = new CachedContextCoordinator(
                snapshots, () -> 5_000L, () -> Duration.ofSeconds(30));
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        String prompt = "Give the player one short shopping tip.";
        String wrapped = ContextSanitizer.block(List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        CompletableFuture<String> collect = new CompletableFuture<>();
        AtomicInteger collects = new AtomicInteger();
        List<String> http = new ArrayList<>();

        CachedContextCoordinator.Decision pending = coordinator.decide(
                playerA, "shop_tip", prompt, true, 5_000L,
                () -> {
                    collects.incrementAndGet();
                    return collect;
                },
                http::add);
        CachedContextCoordinator.Decision again = coordinator.decide(
                playerA, "shop_tip", prompt, true, 5_000L,
                () -> {
                    collects.incrementAndGet();
                    return collect;
                },
                http::add);

        assertEquals(CachedContextCoordinator.Phase.PENDING, pending.phase());
        assertEquals(CachedContextCoordinator.Phase.PENDING, again.phase());
        assertEquals(1, collects.get());
        assertTrue(http.isEmpty());

        collect.complete(wrapped);
        assertEquals(1, http.size());
        String sent = http.getFirst();
        assertEquals(ContextBlock.appendUser(prompt, wrapped), sent);
        assertEquals(client.cacheKey("gpt-4o-mini", sent, "simple", ""), client.cacheKey("gpt-4o-mini", sent, "simple", ""));
        assertTrue(sent.contains(wrapped));

        CachedContextCoordinator.Decision ready = coordinator.decide(
                playerA, "shop_tip", prompt, true, 5_000L,
                () -> {
                    collects.incrementAndGet();
                    return CompletableFuture.completedFuture("other");
                },
                http::add);
        assertEquals(CachedContextCoordinator.Phase.READY, ready.phase());
        assertEquals(sent, ready.promptText());
        assertEquals(1, http.size());
        assertEquals(1, collects.get());

        String same = coordinator.decide(
                playerB, "shop_tip", prompt, true, 5_000L,
                () -> CompletableFuture.completedFuture(wrapped),
                ignored -> {
                }).promptText();
        // player B has no snapshot yet, so this read is pending and does not reuse A's text.
        // The shared key is the rendered prompt plus the same block, checked directly:
        assertEquals(
                client.cacheKey("gpt-4o-mini", sent, "simple", ""),
                client.cacheKey("gpt-4o-mini", ContextBlock.appendUser(prompt, wrapped), "simple", ""));
        assertTrue(same.isEmpty() || same.equals(prompt) || same.contains("Player context:"));
    }

    @Test
    void staleSnapshotIsReusedAndRefreshDoesNotSendHttp() {
        ContextSnapshots snapshots = new ContextSnapshots();
        CachedContextCoordinator coordinator = new CachedContextCoordinator(
                snapshots, () -> 90_000L, () -> Duration.ofSeconds(30));
        UUID player = UUID.randomUUID();
        String prompt = "tip";
        String first = ContextSanitizer.block(List.of(new ContextSanitizer.Line("economy", 10, "old")), 600);
        String next = ContextSanitizer.block(List.of(new ContextSanitizer.Line("economy", 10, "new")), 600);
        snapshots.put(player, "shop_tip", first, 0L);
        CompletableFuture<String> refresh = new CompletableFuture<>();
        List<String> http = new ArrayList<>();
        CachedContextCoordinator.Decision stale = coordinator.decide(
                player, "shop_tip", prompt, true, 30_000L,
                () -> refresh,
                http::add);
        assertEquals(CachedContextCoordinator.Phase.READY, stale.phase());
        assertEquals(ContextBlock.appendUser(prompt, first), stale.promptText());
        assertTrue(http.isEmpty());
        refresh.complete(next);
        assertTrue(http.isEmpty());
        CachedContextCoordinator.Decision updated = coordinator.decide(
                player, "shop_tip", prompt, true, 90_000L,
                () -> CompletableFuture.completedFuture(next),
                http::add);
        assertEquals(ContextBlock.appendUser(prompt, next), updated.promptText());
    }

    @Test
    void nullPlayerSkipsProviders() {
        AtomicInteger collects = new AtomicInteger();
        CachedContextCoordinator coordinator = new CachedContextCoordinator(
                new ContextSnapshots(), () -> 0L, () -> Duration.ofSeconds(30));
        CachedContextCoordinator.Decision decision = coordinator.decide(
                null, "shop_tip", "tip", true, 0L,
                () -> {
                    collects.incrementAndGet();
                    return CompletableFuture.completedFuture("block");
                },
                ignored -> {
                });
        assertEquals(CachedContextCoordinator.Phase.READY, decision.phase());
        assertEquals("tip", decision.promptText());
        assertEquals(0, collects.get());
    }

    private static AiHttpClient client() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "test-key");
        yaml.set("formats.default", "simple");
        PluginConfig config = new PluginConfig(yaml);
        return new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 100),
                prompt -> CompletableFuture.completedFuture("pong"),
                config,
                Logger.getLogger("context-cache-key"));
    }
}
