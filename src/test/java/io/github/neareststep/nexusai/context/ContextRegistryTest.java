package io.github.neareststep.nexusai.context;

import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.NexusAIApi;
import io.github.neareststep.nexusai.api.NexusContextProvider;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRegistryTest {

    @Test
    void ordersByPriorityThenIdAndKeepsTheFirstDuplicate() {
        List<String> lines = new ArrayList<>();
        ContextRegistry registry = registry(lines);
        registry.add("RankBridge", provider("rank", 100));
        registry.add("MyEco", provider("economy", 10));
        registry.add("OtherEco", provider("economy", 1));
        registry.add("QuestBook", provider("Bad-Id", 0));

        assertEquals(List.of("economy", "rank"), registry.ids());
        assertEquals("MyEco", registry.active().getFirst().pluginName());
        assertTrue(lines.stream().anyMatch(line -> line.contains("economy")
                && line.contains("MyEco") && line.contains("OtherEco")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Bad-Id") && line.contains("QuestBook")));
        assertEquals(1, lines.stream().filter(line -> line.contains("already registered")).count());
    }

    @Test
    void unregisterDropsTheProviderWithoutReload() {
        ContextRegistry registry = registry(new ArrayList<>());
        NexusContextProvider economy = provider("economy", 10);
        NexusContextProvider rank = provider("rank", 20);
        registry.add("MyEco", economy);
        registry.add("RankBridge", rank);
        NexusAIApi.bindContextRegistry(registry);
        try {
            assertEquals(List.of("economy", "rank"), NexusAIApi.contextProviderIds());
            assertEquals(3, NexusAIApi.API_VERSION);
            registry.remove(economy);
            assertEquals(List.of("rank"), NexusAIApi.contextProviderIds());
            registry.remove("RankBridge", "rank");
            assertEquals(List.of(), registry.ids());
        } finally {
            NexusAIApi.bindContextRegistry(null);
        }
    }

    @Test
    void unboundApiListsNoProviders() {
        NexusAIApi.bindContextRegistry(null);
        assertEquals(List.of(), NexusAIApi.contextProviderIds());
    }

    private static ContextRegistry registry(List<String> lines) {
        Logger logger = Logger.getLogger("context-registry-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return new ContextRegistry(logger);
    }

    private static NexusContextProvider provider(String id, int priority) {
        return new NexusContextProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Duration timeout() {
                return Duration.ofMillis(100);
            }

            @Override
            public CompletableFuture<String> provide(ContextRequest request) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
