package io.github.neareststep.nexusai.pool;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PoolServiceVarsTest {

    @BeforeEach
    void requireNoEnvKey() {
        assumeTrue(System.getenv("NEXUSAI_API_KEY") == null
                || System.getenv("NEXUSAI_API_KEY").isBlank());
    }

    @Test
    void replenishSendsVarsRulesAppendix() throws Exception {
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        AiProvider provider = prompt -> {
            seenPrompt.set(prompt);
            return CompletableFuture.completedFuture("Welcome, {player_name}!");
        };

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("api.connect-timeout", 5);
        yaml.set("api.read-timeout", 30);
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 1000);
        yaml.set("limits.requests-per-minute", 30);
        yaml.set("limits.requests-per-day", 1000);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        yaml.set("locale", "en");
        yaml.set("pool.enabled", true);
        yaml.set("pool.max-total-prompts", 10);
        yaml.set("pool.entries", List.of(
                Map.of(
                        "prompt", "Short welcome",
                        "size", 1,
                        "min-threshold", 1,
                        "vars", Map.of("player_name", "%player_name%")
                )
        ));
        yaml.set("prewarm.enabled", false);
        yaml.set("prewarm.refresh-before-ttl", 60);
        yaml.set("prewarm.prompts", List.of());

        PluginConfig config = new PluginConfig(yaml);
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        AiPool pool = new AiPool();
        AiHttpClient client = new AiHttpClient(cache, provider, config, Logger.getLogger("test"));
        PoolService service = new PoolService(config, pool, client, Logger.getLogger("test"));
        service.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline && pool.size("Short welcome") < 1) {
            Thread.sleep(20);
        }

        String expected = VarSubstitutor.appendVarsRules(
                "Short welcome", Map.of("player_name", "%player_name%"));
        assertTrue(expected.equals(seenPrompt.get()), "HTTP prompt should include vars rules");
        assertTrue(pool.poll("Short welcome").orElseThrow().contains("{player_name}"));
        service.shutdown();
    }
}
