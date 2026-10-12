package io.github.neareststep.nexusai.prompt;

import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.api.PromptDefinition;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.AiProvider;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.PluginConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCatalogTest {

    @Test
    void loadsStringListAndBlockPrompts() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                ping: "Reply with pong"
                rules:
                  - "Stay fed"
                  - "Sleep at night"
                survival_tips:
                  prompt: |
                    Line one
                    Line two
                  ttl: 90
                  fallback: "no tip"
                  model: survival-model
                  max-prompt-length: 400
                  temperature: 0.2
                """);

        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        PromptCatalog catalog = parsed.catalog();
        assertEquals(List.of("ping", "rules", "survival_tips"), catalog.ids());
        assertEquals("Reply with pong", catalog.find("ping").orElseThrow().template());
        assertEquals("Stay fed\nSleep at night", catalog.find("rules").orElseThrow().template());
        NamedPrompt tips = catalog.find("survival_tips").orElseThrow();
        assertEquals("Line one\nLine two", tips.template());
        assertEquals(Duration.ofSeconds(90), tips.ttl());
        assertEquals("no tip", tips.fallback());
        assertEquals(400, tips.maxPromptLength());
        assertEquals("survival-model", tips.overrides().model("gpt-4o-mini"));
        assertEquals(0.2d, tips.overrides().temperature(null));
    }

    @Test
    void listUnderPromptKeepsNewlinesAndVars() {
        PromptCatalog catalog = PromptCatalog.parse("""
                welcome:
                  prompt:
                    - "Write a one-line welcome for {biome}."
                    - "Do not use markdown."
                  vars:
                    biome: "%player_biome%"
                """).catalog();

        NamedPrompt welcome = catalog.find("welcome").orElseThrow();
        assertEquals("Write a one-line welcome for {biome}.\nDo not use markdown.", welcome.template());
        assertTrue(welcome.playerDependent());
        String rendered = welcome.render(template -> "plains");
        assertEquals(
                "Write a one-line welcome for " + PlayerInput.wrap("plains") + ".\nDo not use markdown.",
                rendered);
        assertTrue(welcome.matchesResolved(rendered));
        assertTrue(welcome.matchesResolved("Write a one-line welcome for desert.\nDo not use markdown."));
        assertFalse(welcome.matchesResolved("Something else"));
    }

    @Test
    void staticVarsAreNotPlayerDependent() {
        NamedPrompt prompt = PromptCatalog.parse("""
                motd:
                  prompt: "Welcome to {server}"
                  vars:
                    server: Nexus
                """).catalog().find("motd").orElseThrow();

        assertFalse(prompt.playerDependent());
        assertEquals("Welcome to " + PlayerInput.wrap("Nexus"), prompt.render(template -> {
            throw new AssertionError(template);
        }));
    }

    @Test
    void skipsInvalidAndEmptyEntries() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                Bad Id: "nope"
                empty: ""
                blank:
                  prompt: "   "
                weird:
                  prompt: 4
                ok: "kept"
                tip:
                  prompt: "hi"
                  colour: red
                  ttl: 0
                  model: ""
                """);

        assertTrue(parsed.valid(), parsed.error());
        assertEquals(List.of("ok", "tip"), parsed.catalog().ids());
        String warnings = String.join("\n", parsed.warnings());
        assertTrue(warnings.contains("Bad Id"), warnings);
        assertTrue(warnings.contains("empty"), warnings);
        assertTrue(warnings.contains("blank"), warnings);
        assertTrue(warnings.contains("weird"), warnings);
        assertTrue(warnings.contains("colour"), warnings);
        assertTrue(warnings.contains("ttl"), warnings);
        assertTrue(warnings.contains("model"), warnings);
        assertNull(parsed.catalog().find("tip").orElseThrow().ttl());
    }

    @Test
    void duplicateIdIsReported() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                alpha: "one"
                alpha: "two"
                """);
        String combined = (parsed.error() == null ? "" : parsed.error()) + " " + parsed.warnings();
        assertTrue(combined.contains("alpha"), combined);
        assertTrue(combined.contains("more than once"), combined);
    }

    @Test
    void sameResolvedTextAndIdCollisionAreReported() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                one: "same text"
                two: "same text"
                hello: "hi there"
                other: "hello"
                """);
        String warnings = String.join("\n", parsed.warnings());
        assertTrue(warnings.contains("one"), warnings);
        assertTrue(warnings.contains("two"), warnings);
        assertTrue(warnings.contains("share a cache entry"), warnings);
        assertTrue(warnings.contains("collides"), warnings);
        assertTrue(warnings.contains("hello"), warnings);
    }

    @Test
    void differentKnowledgeSelectDoesNotShareACacheWarning() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                kw:
                  prompt: "same lore"
                  knowledge-select: keywords
                kwfull:
                  prompt: "same lore"
                  knowledge-select: full
                same1: "same text"
                same2: "same text"
                """);
        String warnings = String.join("\n", parsed.warnings());
        assertFalse(warnings.contains("[kw, kwfull]"), warnings);
        assertFalse(warnings.contains("[kwfull, kw]"), warnings);
        assertTrue(warnings.contains("[same1, same2]"), warnings);
        assertTrue(warnings.contains("share a cache entry"), warnings);
    }

    @Test
    void invalidYamlDoesNotLoadPrompts() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("prompts: [\n");
        assertFalse(parsed.valid());
        assertTrue(parsed.error() != null && !parsed.error().isBlank());
        assertTrue(parsed.catalog().ids().isEmpty());
    }

    @Test
    void commentsOnlyFileIsEmpty() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("# survival_tips:\n#   prompt: hello\n");
        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.catalog().ids().isEmpty());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void unknownIdReferencesIgnoreSentences() {
        PromptCatalog catalog = PromptCatalog.parse("survival_tips: \"Give one tip\"\n").catalog();
        List<String> pool = catalog.unknownIdReferences("pool.entries", List.of(
                "missing_id",
                "survival_tips",
                "Short warm welcome for the joining player"));
        assertEquals(1, pool.size());
        assertTrue(pool.get(0).contains("missing_id"));
        assertTrue(pool.get(0).contains("pool.entries"));
        assertTrue(catalog.unknownIdReferences("prewarm.prompts", List.of("Welcome, {player}")).isEmpty());
    }

    @Test
    void literalFallbackAndCacheKeysStayIsolated() {
        String longBody = "x".repeat(500);
        PromptCatalog catalog = PromptCatalog.parse("""
                biome_tip:
                  prompt: "Tip for {biome}"
                  vars:
                    biome: "%player_biome%"
                  fallback: "no tip"
                static_tip:
                  prompt: "One shared tip"
                long_tip:
                  prompt: "%s"
                capped:
                  prompt: "too long for the cap"
                  max-prompt-length: 4
                  fallback: "capped"
                """.replace("%s", longBody)).catalog();
        PluginConfig config = config();
        AiHttpClient client = client(config);

        ResolvedPrompt plains = catalog.resolve("biome_tip", config, template -> {
            assertEquals("%player_biome%", template);
            return "plains";
        });
        ResolvedPrompt desert = catalog.resolve("biome_tip", config, template -> "desert");
        assertTrue(plains.named());
        assertEquals("Tip for " + PlayerInput.wrap("plains"), plains.text());
        assertEquals("Tip for " + PlayerInput.wrap("desert"), desert.text());
        assertNotEquals(client.cacheKey(plains.text()), client.cacheKey(desert.text()));
        ResolvedPrompt plainsAgain = catalog.resolve("biome_tip", config, template -> "plains");
        assertEquals(client.cacheKey(plains.model(), plains.text()), client.cacheKey(plainsAgain.model(), plainsAgain.text()));

        AtomicInteger calls = new AtomicInteger();
        ResolvedPrompt shared = catalog.resolve("static_tip", config, template -> {
            calls.incrementAndGet();
            return "nope";
        });
        ResolvedPrompt sharedAgain = catalog.resolve("static_tip", config, template -> {
            calls.incrementAndGet();
            return "other";
        });
        assertEquals(0, calls.get());
        assertEquals("One shared tip", shared.text());
        assertEquals(client.cacheKey(shared.text()), client.cacheKey(sharedAgain.text()));

        ResolvedPrompt literal = catalog.resolve("Say hello to the server", config, template -> {
            throw new AssertionError(template);
        });
        assertFalse(literal.named());
        assertEquals("Say hello to the server", literal.text());
        assertTrue(literal.usable());
        assertEquals(client.cacheKey("Say hello to the server"), client.cacheKey(literal.text()));

        ResolvedPrompt missingId = catalog.resolve("not_a_prompt", config, template -> {
            throw new AssertionError(template);
        });
        assertFalse(missingId.named());
        assertEquals("not_a_prompt", missingId.text());

        assertTrue(catalog.resolve("long_tip", config, template -> template).usable());
        assertFalse(catalog.resolve("y".repeat(200), config, template -> template).usable());
        ResolvedPrompt capped = catalog.resolve("capped", config, template -> template);
        assertFalse(capped.usable());
        assertEquals("capped", capped.fallback());
        assertNotEquals(client.cacheKey("model-a", "same"), client.cacheKey("model-b", "same"));
    }

    @Test
    void insertedVarValuesAreNotExpandedAgain() {
        NamedPrompt prompt = PromptCatalog.parse("""
                nested:
                  prompt: "{name} in {biome}"
                  vars:
                    name: "{biome}"
                    biome: "plains"
                """).catalog().find("nested").orElseThrow();
        assertEquals(PlayerInput.wrap("{biome}") + " in " + PlayerInput.wrap("plains"), prompt.render(template -> template));
    }

    @Test
    void resolverMustNotBeNullForAPlayerVar() {
        NamedPrompt prompt = PromptCatalog.parse("""
                biome_tip:
                  prompt: "Tip for {biome}"
                  vars:
                    biome: "%player_biome%"
                """).catalog().find("biome_tip").orElseThrow();
        assertThrows(NullPointerException.class, () -> prompt.render(null));
    }

    @Test
    void contextIsAListOrAllAndUnknownIdsAreWarned() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                shop_tip:
                  prompt: "Give the player one short shopping tip."
                  context: [economy, rank]
                quest_tip:
                  prompt: "One quest hint."
                  context: all
                plain:
                  prompt: "No context."
                broken:
                  prompt: "bad"
                  context: "Not An Id"
                """);
        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.warnings().isEmpty() || parsed.warnings().toString().contains("Not An Id"), parsed.warnings().toString());
        assertTrue(parsed.warnings().toString().contains("Not An Id"));
        assertFalse(parsed.warnings().toString().contains("unknown setting"));
        NamedPrompt shop = parsed.catalog().find("shop_tip").orElseThrow();
        assertEquals(List.of("economy", "rank"), shop.context().ids());
        assertFalse(shop.context().includesAll());
        assertTrue(parsed.catalog().find("quest_tip").orElseThrow().context().includesAll());
        assertFalse(parsed.catalog().find("plain").orElseThrow().context().active());

        List<String> unknown = parsed.catalog().unknownContextProviders(List.of("economy"));
        assertEquals(1, unknown.size());
        assertTrue(unknown.getFirst().contains("rank"));
        assertTrue(parsed.catalog().unknownContextProviders(List.of("economy", "rank")).isEmpty());

        List<String> pool = parsed.catalog().sharedContextWarnings("pool.entries", List.of("shop_tip", "plain"));
        assertEquals(1, pool.size());
        assertTrue(pool.getFirst().contains("context is ignored for pool/prewarm"));
        assertTrue(parsed.catalog().sharedContextWarnings("prewarm.prompts", List.of("quest_tip"))
                .getFirst().contains("context is ignored for pool/prewarm"));
        assertTrue(parsed.catalog().sharedContextWarnings("pool.entries", List.of("plain")).isEmpty());
    }

    @Test
    void namespaceIdsLoadQuotedAndUnquotedAndOldIdsStay() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                survival_tips: "Give one tip"
                quests:intro: "unquoted"
                "shop:price": "quoted"
                'mail:note': "single"
                Bad Id: "nope"
                quests:intro:extra: "too many"
                """);
        assertTrue(parsed.valid(), parsed.error());
        assertEquals(List.of("mail:note", "quests:intro", "shop:price", "survival_tips"), parsed.catalog().ids());
        assertEquals("Give one tip", parsed.catalog().find("survival_tips").orElseThrow().template());
        assertEquals("unquoted", parsed.catalog().find("quests:intro").orElseThrow().template());
        assertEquals("quoted", parsed.catalog().find("shop:price").orElseThrow().template());
        assertEquals("single", parsed.catalog().find("mail:note").orElseThrow().template());
        String warnings = String.join("\n", parsed.warnings());
        assertTrue(warnings.contains("Bad Id"), warnings);
        assertTrue(warnings.contains("quests:intro:extra"), warnings);
        assertTrue(warnings.contains("namespace:id"), warnings);
    }

    @Test
    void duplicateNamespaceIdIsReported() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                "quests:intro": "one"
                "quests:intro": "two"
                """);
        String combined = (parsed.error() == null ? "" : parsed.error()) + " " + parsed.warnings();
        assertTrue(combined.contains("quests:intro"), combined);
        assertTrue(combined.contains("more than once"), combined);
    }

    @Test
    void adminOverrideMayAddContextOnANamespaceId() {
        NamedPrompt prompt = PromptCatalog.parse("""
                "quests:intro":
                  prompt: "from file"
                  context: [economy]
                """).catalog().find("quests:intro").orElseThrow();
        assertEquals("from file", prompt.template());
        assertEquals(List.of("economy"), prompt.context().ids());
    }

    @Test
    void knowledgeSelectAndKeywords() {
        PromptCatalog.Parsed parsed = PromptCatalog.parse("""
                rules_help:
                  prompt: "Answer the player's question about server rules: {question}"
                  knowledge: [rules, faq]
                  knowledge-select: keywords
                  knowledge-keywords: [rules, бан, апелляция]
                plain:
                  prompt: "Hi"
                  knowledge: [lore]
                bad:
                  prompt: "Hi"
                  knowledge-select: vectors
                  knowledge-keywords: "one word"
                """);
        assertTrue(parsed.valid(), parsed.error());
        NamedPrompt rules = parsed.catalog().find("rules_help").orElseThrow();
        assertEquals(KnowledgeSelect.KEYWORDS, rules.knowledgeSelect());
        assertEquals(List.of("rules", "faq"), rules.knowledge());
        assertEquals(List.of("rules", "бан", "апелляция"), rules.knowledgeKeywords());
        NamedPrompt plain = parsed.catalog().find("plain").orElseThrow();
        assertNull(plain.knowledgeSelect());
        assertTrue(plain.knowledgeKeywords().isEmpty());
        assertTrue(parsed.warnings().stream().anyMatch(line -> line.contains("knowledge-select")), parsed.warnings().toString());
        assertEquals(List.of("one word"), parsed.catalog().find("bad").orElseThrow().knowledgeKeywords());
        assertNull(parsed.catalog().find("bad").orElseThrow().knowledgeSelect());

        PromptDefinition definition = PromptDefinition.builder("Answer {question}")
                .knowledge(List.of("rules"))
                .knowledgeSelect(KnowledgeSelect.KEYWORDS)
                .knowledgeKeywords(List.of("бан", " бан "))
                .build();
        assertEquals(KnowledgeSelect.KEYWORDS, definition.knowledgeSelect());
        assertEquals(List.of("бан"), definition.knowledgeKeywords());
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("Answer").knowledgeSelect(null));
        assertThrows(IllegalArgumentException.class, () -> PromptDefinition.builder("Answer").knowledgeKeywords(null));
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.base-url", "https://api.openai.com/v1");
        yaml.set("api.key", "test-key");
        yaml.set("cache.ttl", 300);
        yaml.set("cache.max-size", 100);
        yaml.set("limits.max-prompt-length", 128);
        yaml.set("fallback", "...");
        return new PluginConfig(yaml);
    }

    private static AiHttpClient client(PluginConfig config) {
        AiProvider provider = prompt -> CompletableFuture.completedFuture("ok");
        return new AiHttpClient(
                new AiCache(Duration.ofMinutes(5), 10),
                provider,
                config,
                Logger.getLogger("prompt-keys"));
    }
}
