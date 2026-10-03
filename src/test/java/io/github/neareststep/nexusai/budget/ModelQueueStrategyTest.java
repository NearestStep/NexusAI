package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.ChatCaller;
import io.github.neareststep.nexusai.ai.ChatExchange;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.command.NaiCommand;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.QueueEntryConfig;
import io.github.neareststep.nexusai.config.QueueStrategy;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelQueueStrategyTest {

    @Test
    void roundRobinSplitsThreeRowsEvenly() {
        ModelQueue queue = queue(QueueStrategy.ROUND_ROBIN, 0);
        int[] starts = new int[3];
        for (int i = 0; i < 300; i++) {
            starts[queue.select(1_000L).orElseThrow().index()]++;
        }
        assertEquals(100, starts[0]);
        assertEquals(100, starts[1]);
        assertEquals(100, starts[2]);
    }

    @Test
    void aDisabledRowSharesItsTrafficWithTheOthers() {
        ModelQueue queue = queue(QueueStrategy.ROUND_ROBIN, 0);
        queue.cooldown(1, Long.MAX_VALUE, ModelQueue.Hold.ERROR);
        int[] starts = new int[3];
        for (int i = 0; i < 300; i++) {
            starts[queue.select(1_000L).orElseThrow().index()]++;
        }
        assertEquals(150, starts[0]);
        assertEquals(0, starts[1]);
        assertEquals(150, starts[2]);
    }

    @Test
    void roundRobinSkipsCooldownDailyCapAndRemainingThreshold() {
        ModelQueue cooled = queue(QueueStrategy.ROUND_ROBIN, 0);
        cooled.cooldown(0, 50_000L, ModelQueue.Hold.ERROR);
        assertEquals("groq", cooled.nextStart(1_000L).orElseThrow().provider());
        assertEquals(1, cooled.select(1_000L).orElseThrow().index());

        ModelQueue capped = queue(QueueStrategy.ROUND_ROBIN, 1);
        assertTrue(capped.tryConsume(0, 1_000L));
        assertEquals("groq", capped.select(1_000L).orElseThrow().provider());

        ModelQueue low = queue(QueueStrategy.ROUND_ROBIN, 0, 0);
        low.observe(0, Map.of("x-ratelimit-remaining-requests", List.of("0")), 1_000L);
        assertEquals("groq", low.select(2_000L).orElseThrow().provider());
    }

    @Test
    void failoverAlwaysStartsAtTheFirstAvailableRow() {
        ModelQueue queue = queue(QueueStrategy.FAILOVER, 0);
        for (int i = 0; i < 20; i++) {
            List<ModelQueue.Choice> order = queue.selectable(1_000L);
            assertEquals(0, order.getFirst().index());
            assertEquals("openai", order.get(0).provider());
            assertEquals("groq", order.get(1).provider());
            assertEquals("gemini", order.get(2).provider());
        }
        assertEquals("openai", queue.nextStart(1_000L).orElseThrow().provider());
    }

    @Test
    void roundRobinFailureWalksTheCircleThenTheNextRequestContinues() {
        PluginConfig config = providers();
        ModelQueue queue = new ModelQueue(
                List.of(
                        new QueueEntryConfig("openai", "gpt-4o-mini", 0),
                        new QueueEntryConfig("groq", "llama", 0),
                        new QueueEntryConfig("gemini", "gemini-flash", 0)
                ),
                0,
                60_000L,
                300_000L,
                null,
                Logger.getLogger("rr-failover"),
                QueueStrategy.ROUND_ROBIN);
        List<String> seen = new ArrayList<>();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            seen.add(model);
            if ("gpt-4o-mini".equals(model)) {
                throw new AiRequestException(AiErrorKind.OTHER, 500, "HTTP 500", null);
            }
            return new ChatExchange("ok-" + model, Map.of());
        };
        ExecutorService executor = daemonExecutor();
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, executor, Logger.getLogger("rr-failover"));
        assertEquals("ok-llama", provider.answer("ping", GenerationOverrides.none(), false).join().text());
        assertEquals(List.of("gpt-4o-mini", "llama"), List.copyOf(seen));
        assertEquals("groq", queue.nextStart(System.currentTimeMillis()).orElseThrow().provider());
        executor.shutdownNow();
    }

    @Test
    void failoverModeDoesNotRotateTheStart() {
        PluginConfig config = providers();
        ModelQueue queue = new ModelQueue(
                List.of(
                        new QueueEntryConfig("openai", "gpt-4o-mini", 0),
                        new QueueEntryConfig("groq", "llama", 0)
                ),
                0,
                60_000L,
                300_000L,
                null,
                Logger.getLogger("failover-stable"),
                QueueStrategy.FAILOVER);
        AtomicInteger calls = new AtomicInteger();
        ChatCaller http = (prompt, overrides, baseUrl, apiKey, model) -> {
            calls.incrementAndGet();
            return new ChatExchange(model, Map.of());
        };
        ExecutorService executor = daemonExecutor();
        RoutingProvider provider = new RoutingProvider(
                config, queue, http, executor, Logger.getLogger("failover-stable"));
        for (int i = 0; i < 5; i++) {
            assertEquals("gpt-4o-mini", provider.answer("ping", GenerationOverrides.none(), false).join().text());
        }
        assertEquals(5, calls.get());
        executor.shutdownNow();
    }

    @Test
    void unknownStrategyFallsBackAndStatusNamesTheNextRow() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("model-queue-strategy", "round_robin");
        PluginConfig config = new PluginConfig(yaml);
        assertEquals(QueueStrategy.ROUND_ROBIN, config.modelQueueStrategy());
        assertNull(config.modelQueueStrategyWarning());

        yaml.set("model-queue-strategy", "shuffle");
        config.reload(yaml);
        assertEquals(QueueStrategy.FAILOVER, config.modelQueueStrategy());
        assertTrue(config.modelQueueStrategyWarning().contains("shuffle"));
        assertTrue(config.modelQueueStrategyWarning().contains("failover"));

        assertEquals("failover (next: none)", NaiCommand.modelQueueStrategyText(null, " "));
        assertEquals(
                "round-robin (next: groq/llama)",
                NaiCommand.modelQueueStrategyText(QueueStrategy.ROUND_ROBIN, "groq/llama"));
    }

    @Test
    void mergerAppendsTheStrategyWithoutReplacingAUserValue() {
        String existing = """
                config-version: 2
                model-queue-strategy: round-robin
                api:
                  provider: groq
                """;
        String defaults = """
                config-version: 2
                model-queue-strategy: failover
                api:
                  provider: openai
                  model: gpt-4o-mini
                """;
        var kept = io.github.neareststep.nexusai.config.ConfigMerger.mergeMissing(existing, defaults);
        assertTrue(kept.addedKeys().contains("api.model"));
        assertTrue(!kept.addedKeys().contains("model-queue-strategy"));
        YamlConfiguration parsed = YamlConfiguration.loadConfiguration(new java.io.StringReader(kept.yaml()));
        assertEquals("round-robin", parsed.getString("model-queue-strategy"));
        assertEquals(2, parsed.getInt("config-version"));

        String older = """
                config-version: 2
                api:
                  provider: groq
                """;
        var added = io.github.neareststep.nexusai.config.ConfigMerger.mergeMissing(older, defaults);
        assertTrue(added.addedKeys().contains("model-queue-strategy"));
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new java.io.StringReader(added.yaml()));
        assertEquals("failover", migrated.getString("model-queue-strategy"));
        assertEquals(2, migrated.getInt("config-version"));
        assertEquals("groq", migrated.getString("api.provider"));
    }

    private static ModelQueue queue(QueueStrategy strategy, int firstLimit) {
        return queue(strategy, firstLimit, 0);
    }

    private static ModelQueue queue(QueueStrategy strategy, int firstLimit, int threshold) {
        return new ModelQueue(
                List.of(
                        new QueueEntryConfig("openai", "gpt-4o-mini", firstLimit),
                        new QueueEntryConfig("groq", "llama", 0),
                        new QueueEntryConfig("gemini", "gemini-flash", 0)
                ),
                threshold,
                60_000L,
                300_000L,
                null,
                () -> 0L,
                () -> LocalDate.of(2026, 10, 3),
                ZoneId.of("UTC"),
                Logger.getLogger("queue-strategy-" + strategy.wire() + "-" + firstLimit + "-" + threshold),
                strategy
        );
    }

    private static PluginConfig providers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "");
        yaml.set("fallback", "...");
        yaml.set("limits.provider-pause-seconds", 60);
        yaml.set("limits.auth-pause-seconds", 300);
        yaml.set("providers.openai.type", "openai-compatible");
        yaml.set("providers.openai.url", "https://api.openai.com/v1");
        yaml.set("providers.openai.api-key", "sk-one-1111");
        yaml.set("providers.groq.type", "openai-compatible");
        yaml.set("providers.groq.url", "https://api.groq.com/openai/v1");
        yaml.set("providers.groq.api-key", "sk-groq-3333");
        yaml.set("providers.gemini.type", "gemini");
        yaml.set("providers.gemini.url", "https://generativelanguage.googleapis.com/v1beta/openai");
        yaml.set("providers.gemini.api-key", "sk-gem-4444");
        return new PluginConfig(yaml);
    }

    private static ExecutorService daemonExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "queue-strategy-test-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
