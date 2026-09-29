package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NamedPoolTest {

    @Test
    void playerSpecificPromptsDoNotShareAPool() {
        assumeTrue(System.getenv("NEXUSAI_API_KEY") == null || System.getenv("NEXUSAI_API_KEY").isBlank());
        PromptCatalog catalog = PromptCatalog.parse("""
                biome_tip:
                  prompt: "Tip for {biome}"
                  vars:
                    biome: "%player_biome%"
                """).catalog();
        PluginConfig config = config("biome_tip", 1, 1);
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = prompt -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture("ans:" + prompt);
        };
        AiPool pool = new AiPool();
        PoolService service = new PoolService(
                config,
                pool,
                new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), provider, config, Logger.getLogger("named-pool")),
                Logger.getLogger("named-pool"),
                PoolStore.disabled(),
                null,
                catalog);
        service.start();
        assertEquals(0, calls.get());
        assertEquals(0, pool.size("biome_tip"));

        service.onConsume("biome_tip", "Tip for plains");
        service.onConsume("biome_tip", "Tip for desert");

        assertEquals(List.of("ans:Tip for plains"), pool.copy("Tip for plains"));
        assertEquals(List.of("ans:Tip for desert"), pool.copy("Tip for desert"));
        assertEquals(0, pool.size("biome_tip"));
        assertEquals(2, calls.get());
    }

    @Test
    void staticNamedPromptIsStoredUnderTheResolvedText() {
        assumeTrue(System.getenv("NEXUSAI_API_KEY") == null || System.getenv("NEXUSAI_API_KEY").isBlank());
        PromptCatalog catalog = PromptCatalog.parse("""
                rules:
                  prompt:
                    - "Stay fed"
                    - "Sleep"
                """).catalog();
        PluginConfig config = config("rules", 1, 1);
        AiProvider provider = prompt -> CompletableFuture.completedFuture("ans:" + prompt);
        AiPool pool = new AiPool();
        PoolService service = new PoolService(
                config,
                pool,
                new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), provider, config, Logger.getLogger("named-pool")),
                Logger.getLogger("named-pool"),
                PoolStore.disabled(),
                null,
                catalog);
        service.start();
        assertEquals(List.of("ans:Stay fed\nSleep"), pool.copy("Stay fed\nSleep"));
        assertEquals(0, pool.size("rules"));
        assertTrue(catalog.staticText("rules").contains("\n"));
    }

    private static PluginConfig config(String prompt, int size, int minThreshold) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 100);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        yaml.set("pool.enabled", true);
        yaml.set("pool.max-total-prompts", 10);
        yaml.set("pool.entries", List.of(Map.of(
                "prompt", prompt,
                "size", size,
                "min-threshold", minThreshold)));
        yaml.set("prewarm.enabled", false);
        yaml.set("prewarm.prompts", List.of());
        return new PluginConfig(yaml);
    }
}
