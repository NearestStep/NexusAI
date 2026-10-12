package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextBlock;
import io.github.neareststep.nexusai.context.ContextSanitizer;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.pool.PoolKeys;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 1.1.x cache keys for placeholder, prewarm, pool rows, and talk greetings.
 * Dialogue turns, summaries, and moderation are not cached.
 * <p>
 * The fixtures are ordinary prose. They do not contain vendor-key shapes, so a later
 * change to {@code SecretMask} does not require editing these keys.
 */
class CacheKeyGoldenTest {

    private static final String MODEL = "gpt-4o-mini";
    private static final String VERSION = "player-input-guard-v8";
    private static final String SHOP = "Give the player one short shopping tip.";
    private static final String KNOWLEDGE_TOKEN = "ea913e7aa7a6a933";
    private static final String GREETING_SHEET_HASH =
            "a2fe1ffad9b59d902316312a5fdb7c7699e16929c8e1016f4b906f744fe132c0";
    private static final String GREETING_CONTEXT_HASH =
            "dc915e74226b589524f2ead0c52ebce531c8308b1447c7f3bd2c97a11a945677";

    @Test
    void placeholderWithoutKnowledgeKeepsTheHistoricalKey() {
        AiHttpClient client = client();
        ResolvedPrompt resolved = ResolvedPrompt.literal(SHOP, config());
        String key = client.cacheKey(resolved.model(), resolved.text(), resolved.formatId(), "");
        assertEquals(MODEL + "\u0000simple\u0000" + VERSION + "\u0000" + SHOP, key);
        assertEquals(VERSION, PlayerInput.KEY_VERSION);
        assertEquals(key, client.cacheKey(MODEL, SHOP, "simple", null));
        assertEquals(key, client.cacheKey(MODEL, SHOP, "simple", "   "));
        assertEquals(key, client.cacheKey("", SHOP));
    }

    @Test
    void placeholderKeyStaysOnTheConfigModelWhenTheQueueModelDiffers() {
        ResolvedPrompt resolved = ResolvedPrompt.literal(SHOP, config());
        String key = client().cacheKey(resolved.model(), resolved.text(), resolved.formatId(), "");
        assertEquals(MODEL, resolved.model());
        assertFalse(key.contains("llama-3.3-70b-versatile"));
        assertEquals(MODEL + "\u0000simple\u0000" + VERSION + "\u0000" + SHOP, key);
    }

    @Test
    void placeholderKnowledgeInsertsTheBlockHash(@TempDir Path dir) throws Exception {
        PluginConfig config = config();
        KnowledgeBase knowledge = lore(dir);
        PromptCatalog catalog = prompts("""
                harbor:
                  prompt: "Where is the harbor?"
                  format: chat
                  model: llama-3.1-8b
                  knowledge:
                    - lore
                """);
        ResolvedPrompt resolved = catalog.resolve("harbor", config, value -> value);
        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                resolved.overrides(), config.getSystemPrompt(), knowledge, resolved.knowledge());
        assertEquals(KNOWLEDGE_TOKEN, prepared.cacheToken());
        String key = client().cacheKey(
                resolved.model(), resolved.text(), resolved.formatId(), prepared.cacheToken());
        assertEquals(
                "llama-3.1-8b\u0000chat\u0000" + VERSION + "\u0000" + KNOWLEDGE_TOKEN + "\u0000Where is the harbor?",
                key);
    }

    @Test
    void placeholderContextChangesThePromptFieldAndNotTheKeyShape() {
        String block = ContextSanitizer.block(
                List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        String prompt = ContextBlock.appendUser(SHOP, block);
        assertEquals(
                SHOP + "\n\nPlayer context:\n§§§ PLAYER INPUT §§§\neconomy: ~12k\n§§§ END §§§",
                prompt);
        String key = client().cacheKey(MODEL, prompt, "simple", "");
        assertEquals(MODEL + "\u0000simple\u0000" + VERSION + "\u0000" + prompt, key);
        assertFalse(key.contains(KNOWLEDGE_TOKEN));
    }

    @Test
    void formatNameIsNormalizedInsideTheKey() {
        String key = client().cacheKey(MODEL, SHOP, "Chat", "");
        assertEquals(MODEL + "\u0000chat\u0000" + VERSION + "\u0000" + SHOP, key);
    }

    @Test
    void prewarmUsesTheSameKeyAsACachedPlaceholder(@TempDir Path dir) throws Exception {
        PluginConfig config = config();
        KnowledgeBase knowledge = lore(dir);
        NamedPrompt named = prompts("""
                harbor_tip:
                  prompt: "Name one landmark in the harbor."
                  format: hologram
                  knowledge:
                    - lore
                """).find("harbor_tip").orElseThrow();
        String text = named.render(value -> value);
        String format = config.normalizeFormat(named.format());
        KnowledgeComposer.Prepared prepared = KnowledgeComposer.prepare(
                named.overrides().withFormat(format), config.getSystemPrompt(), knowledge, named.knowledge());
        String key = client().cacheKey(
                prepared.overrides().model(config.getModel()),
                text,
                prepared.overrides().formatOr(config.defaultFormatId()),
                prepared.cacheToken());
        assertEquals(KNOWLEDGE_TOKEN, prepared.cacheToken());
        assertEquals(
                MODEL + "\u0000hologram\u0000" + VERSION + "\u0000" + KNOWLEDGE_TOKEN
                        + "\u0000Name one landmark in the harbor.",
                key);
    }

    @Test
    void poolMemoryKeyKeepsSimpleRowsUnprefixed() {
        NamedPrompt named = prompts("""
                welcome:
                  prompt: "Write a one-line welcome for {player}."
                  format: chat
                  vars:
                    player: traveler
                """).find("welcome").orElseThrow();
        String text = named.render(value -> value);
        assertEquals(
                "Write a one-line welcome for §§§ PLAYER INPUT §§§\ntraveler\n§§§ END §§§.",
                text);
        assertEquals(text, PoolKeys.memory("simple", text));
        assertEquals(text, PoolKeys.memory("SIMPLE", text));
        assertEquals("chat\u0000" + text, PoolKeys.memory("chat", text));
        assertEquals("chat\u0000" + text, PoolKeys.memory("Chat", text));
    }

    @Test
    void talkGreetingKeyIsTheCharacterAndTheSheetHash() {
        PluginConfig config = config();
        NamedPrompt bram = prompts("""
                bram:
                  prompt: "You are Bram, the harbor keeper."
                  format: chat
                """).find("bram").orElseThrow();
        String system = DialogueService.characterSystem(bram, config, null);
        assertEquals("bram\u0000" + GREETING_SHEET_HASH, GreetingCache.key("bram", system));
    }

    @Test
    void talkGreetingKeyChangesWhenContextIsSplicedIntoTheSheet() {
        PluginConfig config = config();
        NamedPrompt bram = prompts("""
                bram:
                  prompt: "You are Bram, the harbor keeper."
                  format: chat
                """).find("bram").orElseThrow();
        String instruction = DialogueService.formatInstruction(bram, config);
        String sheet = DialogueService.characterSystem(bram, config, null);
        String block = ContextSanitizer.block(
                List.of(new ContextSanitizer.Line("economy", 10, "~12k")), 600);
        String system = ContextBlock.spliceSystem(sheet, instruction, block);
        assertEquals("bram\u0000" + GREETING_CONTEXT_HASH, GreetingCache.key("bram", system));
        assertFalse(system.contains("sk-"));
    }

    private static KnowledgeBase lore(Path dir) throws Exception {
        Files.writeString(dir.resolve("lore.md"), "<!-- harbor note -->\nThe harbor is old.\n");
        return KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("golden-cache"));
    }

    private static PromptCatalog prompts(String yaml) {
        PromptCatalog.Parsed parsed = PromptCatalog.parse(yaml);
        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        return parsed.catalog();
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", MODEL);
        yaml.set("api.key", "test-key");
        yaml.set("formats.default", "simple");
        return new PluginConfig(yaml);
    }

    private static AiHttpClient client() {
        return new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 100),
                prompt -> CompletableFuture.completedFuture("pong"),
                config(),
                Logger.getLogger("golden-cache"));
    }
}
