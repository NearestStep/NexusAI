package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.context.ContextBlock;
import io.github.neareststep.nexusai.json.StructuredOutputSupport;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.knowledge.KeywordSettings;
import io.github.neareststep.nexusai.knowledge.KnowledgeRequest;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.PromptCatalog;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueKnowledgeTest {

    @Test
    void talkInsertsKeywordsAfterSummaryAndBeforeContext(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), """
                # Harbor

                Ships pay the dock fee.

                # Appeals

                <!-- keywords: appeal, ban -->
                Write to staff to file an appeal after a ban.
                """);
        KnowledgeBase knowledge = KnowledgeBase.load(
                dir, 6000, 4000, KeywordSettings.defaults(), false, new ArrayList<>(), Logger.getLogger("talk-knowledge"), List.of());
        NamedPrompt keywords = prompt("""
                keeper:
                  prompt: "You are the harbor keeper."
                  format: chat
                  knowledge: [rules]
                  knowledge-select: keywords
                  knowledge-keywords: [appeal]
                  dialogue:
                    greeting: "Hello"
                """);
        NamedPrompt full = prompt("""
                keeper:
                  prompt: "You are the harbor keeper."
                  format: chat
                  knowledge: [rules]
                  dialogue:
                    greeting: "Hello"
                """);
        PluginConfig config = config();
        assertEquals("", DialogueService.talkKnowledge(full, config, knowledge, "I want to appeal", "ban"));

        String greeting = DialogueService.talkKnowledge(keywords, config, knowledge, "  ", "");
        assertTrue(greeting.contains("file an appeal"), greeting);
        assertFalse(greeting.contains("<!--"), greeting);

        String block = DialogueService.talkKnowledge(keywords, config, knowledge, "hello", "I want to appeal a ban");
        assertTrue(block.contains("file an appeal"), block);
        assertTrue(block.contains("# Appeals"), block);
        assertTrue(block.contains("[rules]"), block);

        String summary = "Summary of the docks.";
        String system = DialogueService.characterSystem(keywords, config, null, summary, block);
        String instruction = DialogueService.formatInstruction(keywords, config);
        assertTrue(system.indexOf(summary) < system.indexOf(KnowledgeBase.OPEN), system);
        assertTrue(system.indexOf(KnowledgeBase.OPEN) < system.indexOf(instruction), system);

        String spliced = ContextBlock.spliceSystem(system, instruction, "balance 10");
        GenerationOverrides overrides = GenerationOverrides.none().withSystemPrompt(spliced);
        String sent = OpenAiProvider.buildBody(config, PlayerInput.wrap("I want to appeal"), overrides)
                .getMessages().getFirst().getContent();
        int summaryAt = sent.indexOf(summary);
        int knowledgeAt = sent.indexOf(KnowledgeBase.OPEN);
        int contextAt = sent.indexOf("Player context:");
        int instructionAt = sent.indexOf(instruction);
        int guardAt = sent.indexOf(PlayerInput.GUARD);
        assertTrue(summaryAt < knowledgeAt, sent);
        assertTrue(knowledgeAt < contextAt, sent);
        assertTrue(contextAt < instructionAt, sent);
        assertTrue(instructionAt < guardAt, sent);
        assertTrue(sent.stripTrailing().endsWith(PlayerInput.GUARD), sent);
    }

    @Test
    void appealQueryPicksTheAppealParagraphsFirst(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), rules());
        Files.writeString(dir.resolve("lore.md"), "The old harbor.\n\n".repeat(2_000));
        Files.writeString(dir.resolve("faq.md"), "Ask in the help room.\n\n".repeat(2_000));
        long size = Files.size(dir.resolve("rules.md")) + Files.size(dir.resolve("lore.md")) + Files.size(dir.resolve("faq.md"));
        assertTrue(size >= 60_000, "fixture is " + size + " bytes");
        KeywordSettings settings = new KeywordSettings(3, 1200, 200_000, 1, KeywordSettings.OnNoMatch.NONE, List.of());
        KnowledgeBase knowledge = KnowledgeBase.load(
                dir, 6000, 4000, settings, false, new ArrayList<>(), Logger.getLogger("appeal"), List.of());
        KnowledgeBase.Piece piece = knowledge.render(
                List.of("rules", "lore", "faq"),
                new KnowledgeRequest(KnowledgeSelect.KEYWORDS, "как подать апелляцию на бан", List.of()));
        String block = piece.block();
        assertTrue(block.contains("[rules]"), block);
        assertTrue(block.contains("# Апелляции"), block);
        assertTrue(block.contains("Чтобы подать апелляцию"), block);
        assertFalse(block.contains("<!--"), block);
        assertTrue(block.indexOf("номер 1") < block.indexOf("Чтобы подать апелляцию"), block);
        assertFalse(block.contains("номер 99"), block);
        assertTrue(piece.summary().contains("rules#4"), piece.summary());
        assertTrue(piece.summary().endsWith("(keywords)"), piece.summary());
        assertFalse(block.contains("[lore]"));
        assertFalse(block.contains("[faq]"));
    }

    @Test
    void quotaReserveGrowsWhenTheBlockIsInTheSystem(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), "Appeal to staff.\n");
        KnowledgeBase knowledge = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("quota-knowledge"));
        PluginConfig config = config();
        String block = knowledge.block(List.of("rules"));
        GenerationOverrides bare = GenerationOverrides.none().withSystemPrompt("short");
        GenerationOverrides withKnowledge = GenerationOverrides.none().withSystemPrompt("short\n\n" + block);
        assertTrue(QuotaEstimates.forCall(config, "hello", withKnowledge, "gpt-4o-mini")
                > QuotaEstimates.forCall(config, "hello", bare, "gpt-4o-mini"));
    }

    @Test
    void jsonInstructionStaysAfterKnowledgeAndBeforeTheGuard(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), "Appeal to staff.\n");
        KnowledgeBase knowledge = KnowledgeBase.load(dir, 6000, 4000, new ArrayList<>(), Logger.getLogger("json-knowledge"));
        PluginConfig config = config();
        String system = "You are a clerk.\n\n" + knowledge.block(List.of("rules"));
        JsonSchema schema = JsonSchema.parse("""
                {"type":"object","additionalProperties":false,"required":["ok"],
                 "properties":{"ok":{"type":"boolean"}}}
                """);
        GenerationOverrides overrides = StructuredOutputSupport.call(
                GenerationOverrides.none().withSystemPrompt(system), schema);
        String sent = OpenAiProvider.buildBody(config, PlayerInput.wrap("appeal"), overrides)
                .getMessages().getFirst().getContent();
        int knowledgeAt = sent.indexOf(KnowledgeBase.OPEN);
        int jsonAt = sent.indexOf("Reply with one JSON object");
        int guardAt = sent.indexOf(PlayerInput.GUARD);
        assertTrue(knowledgeAt >= 0 && knowledgeAt < jsonAt, sent);
        assertTrue(jsonAt < guardAt, sent);
    }

    @Test
    void previousPlayerLineDoesNotTrimMemory() {
        TurnMemory memory = new TurnMemory();
        memory.add("user", "first", 1L);
        memory.add("assistant", "ok", 2L);
        memory.add("user", "second appeal", 3L);
        assertEquals("second appeal", memory.lastUserText());
        assertEquals(3, memory.view().size());

        MemoryStore store = new MemoryStore();
        UUID player = UUID.randomUUID();
        store.append(player, "keeper", "user", "earlier appeal", 1_000L, 8, 4000, 0L);
        store.append(player, "keeper", "assistant", "noted", 1_001L, 8, 4000, 0L);
        assertEquals("earlier appeal", store.lastUserLine(player, "keeper", 1_002L, 0L));
        assertEquals(2, store.get(player, "keeper").view().size());
    }

    private static String rules() {
        return """
                # Наказания

                Игрок номер 1 получил бан на сутки.

                Игрок номер 2 получил бан на сутки.

                Игрок номер 99 получил бан на сутки.

                # Апелляции

                <!-- keywords: апелляция, бан -->
                Чтобы подать апелляцию, напишите персоналу сервера.
                """;
    }

    private static NamedPrompt prompt(String yaml) {
        PromptCatalog.Parsed parsed = PromptCatalog.parse(yaml);
        assertTrue(parsed.valid(), parsed.error());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        return parsed.catalog().find("keeper").orElseThrow();
    }

    private static PluginConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("api.provider", "openai");
        yaml.set("api.model", "gpt-4o-mini");
        yaml.set("api.key", "test-key");
        yaml.set("api.system-prompt", "Be brief.");
        yaml.set("formats.default", "simple");
        return new PluginConfig(yaml);
    }
}
