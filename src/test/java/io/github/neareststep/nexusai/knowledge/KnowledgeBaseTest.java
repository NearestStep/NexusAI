package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseTest {

    @Test
    void blockOrderIsLoreThenRules(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "The harbor is old.");
        Files.writeString(dir.resolve("rules.txt"), "No griefing.");
        KnowledgeBase knowledge = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("kb-order"));
        String block = knowledge.block(List.of("lore", "rules"));
        assertTrue(block.startsWith(KnowledgeBase.OPEN));
        assertTrue(block.endsWith(KnowledgeBase.CLOSE));
        assertTrue(block.indexOf("[lore]") < block.indexOf("The harbor is old."));
        assertTrue(block.indexOf("The harbor is old.") < block.indexOf("[rules]"));
        assertTrue(block.indexOf("[rules]") < block.indexOf("No griefing."));
    }

    @Test
    void perFileCapAndRequestCapTruncateOnce(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "abcdef");
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("kb-trunc-" + dir.getFileName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.WARNING);
        List<String> logged = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        KnowledgeBase fileCapped = KnowledgeBase.load(dir, 6000, 3, warnings, logger);
        assertTrue(warnings.stream().anyMatch(line -> line.contains("truncated to 3")));
        String fileBlock = fileCapped.block(List.of("lore"));
        assertTrue(fileBlock.contains("abc"));
        assertFalse(fileBlock.contains("def"));

        KnowledgeBase requestCapped = KnowledgeBase.load(dir, 8, 4000, new ArrayList<>(), logger);
        String first = requestCapped.block(List.of("lore"));
        String second = requestCapped.block(List.of("lore"));
        assertEquals(first, second);
        assertTrue(first.startsWith(KnowledgeBase.OPEN));
        assertTrue(first.endsWith(KnowledgeBase.CLOSE));
        assertFalse(first.contains("abcdef"));
        assertEquals(1, logged.stream().filter(line -> line.contains("truncated to 8")).count());
    }

    @Test
    void unknownNameIsOmittedAndCacheKeyChangesWhenTheFileChanges(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "alpha");
        KnowledgeBase first = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("kb-hash"));
        assertEquals("", first.block(List.of("missing")));
        String token = first.cacheToken(List.of("lore"));
        AiHttpClient client = client();
        String key = client.cacheKey("gpt-4o-mini", "hello", "simple", token);

        Files.writeString(dir.resolve("lore.md"), "beta");
        KnowledgeBase second = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("kb-hash-2"));
        String changed = client.cacheKey("gpt-4o-mini", "hello", "simple", second.cacheToken(List.of("lore")));
        assertNotEquals(key, changed);
        assertEquals(client.cacheKey("gpt-4o-mini", "hello", "simple"), client.cacheKey("gpt-4o-mini", "hello", "simple", ""));
    }

    @Test
    void exampleFileIsCreatedOnce(@TempDir Path dir) throws Exception {
        KnowledgeBase.ensureExample(dir);
        Path example = dir.resolve(KnowledgeBase.EXAMPLE_FILE);
        assertTrue(Files.isRegularFile(example));
        Files.writeString(example, "custom lore", StandardCharsets.UTF_8);
        KnowledgeBase.ensureExample(dir);
        assertEquals("custom lore", Files.readString(example));
    }

    @Test
    void promptKnowledgeAndFallbackModelAreLoaded() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                guide:
                  prompt: "Answer in character."
                  knowledge:
                    - lore
                    - rules
                  fallback-model:
                    provider: ollama
                    model: llama3.2
                bad:
                  prompt: "x"
                  knowledge: 4
                  fallback-model: "nope"
                """);
        assertTrue(parsed.valid(), parsed.error());
        assertEquals(List.of("lore", "rules"), parsed.catalog().find("guide").orElseThrow().knowledge());
        assertEquals("ollama", parsed.catalog().find("guide").orElseThrow().fallbackModel().provider());
        assertEquals("llama3.2", parsed.catalog().find("guide").orElseThrow().fallbackModel().model());
        String warnings = String.join("\n", parsed.warnings());
        assertTrue(warnings.contains("knowledge"), warnings);
        assertTrue(warnings.contains("fallback-model"), warnings);
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "test-key");
        yaml.set("fallback", "...");
        return new PluginConfig(yaml);
    }

    private static AiHttpClient client() {
        PluginConfig config = config();
        AiProvider provider = prompt -> CompletableFuture.completedFuture("ok");
        return new AiHttpClient(new AiCache(Duration.ofMinutes(5), 10), provider, config, Logger.getLogger("kb-cache"));
    }
}
