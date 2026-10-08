package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.knowledge.KeywordSettings.OnNoMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIndexTest {

    @Test
    void paragraphsSectionsAndComments() {
        String rules = """
                # Harbor

                <!-- keywords: mute -->

                A mute lasts until staff review it.

                Staff keep <!-- internal --> notes in this paragraph. <!-- keywords: appeal -->
                """;
        Tokenizer tokenizer = Tokenizer.builtin(List.of());
        KnowledgeIndex index = KnowledgeIndex.build(Map.of("rules", rules), tokenizer);
        assertEquals(2, index.paragraphCount());
        assertEquals("# Harbor", index.paragraph(0).heading());
        assertEquals(1, index.paragraph(0).number());
        assertFalse(index.paragraph(0).text().contains("<!--"));
        assertTrue(index.paragraph(0).text().contains("mute"));
        assertEquals(3, index.paragraph(0).weight("mute"));
        assertFalse(index.paragraph(1).text().contains("<!--"));
        assertTrue(index.paragraph(1).text().contains("notes"));
        assertEquals(3, index.paragraph(1).weight("appeal"));
        assertEquals(2, index.paragraph(0).weight("harbor"));
    }

    @Test
    void keywordFileCapWarnsOnceAndFullCapWaitsForUse(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), "a".repeat(30));
        List<String> warnings = new ArrayList<>();
        Logger logger = Logger.getLogger("knowledge-cap");
        List<String> logged = new ArrayList<>();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        KeywordSettings keywords = new KeywordSettings(6, 1200, 10, 1, OnNoMatch.NONE, List.of());
        KnowledgeBase loaded = KnowledgeBase.load(dir, 6000, 20, keywords, false, warnings, logger, List.of());
        assertTrue(warnings.stream().anyMatch(line -> line.contains("truncated to 10")), warnings.toString());
        assertFalse(warnings.stream().anyMatch(line -> line.contains("truncated to 20")), warnings.toString());
        loaded.warnFullModeTruncation(List.of("other"), logger);
        assertTrue(logged.isEmpty(), logged.toString());
        loaded.warnFullModeTruncation(List.of("rules"), logger);
        loaded.warnFullModeTruncation(List.of("rules"), logger);
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.getFirst().contains("truncated to 20"));
        assertTrue(loaded.block(List.of("rules")).contains("a".repeat(20)));
        assertFalse(loaded.block(List.of("rules")).contains("a".repeat(21)));
    }

    @Test
    void reloadKeepsThePreviousIndex(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("rules.md"), """
                # Rules

                Ban appeals go to staff.
                """);
        Logger logger = Logger.getLogger("knowledge-reload");
        KeywordSettings settings = new KeywordSettings(6, 1200, 200_000, 1, OnNoMatch.NONE, List.of());
        KnowledgeBase first = KnowledgeBase.load(dir, 6000, 4000, settings, false, new ArrayList<>(), logger, List.of());
        KnowledgeRequest query = new KnowledgeRequest(KnowledgeSelect.KEYWORDS, "appeal", List.of());
        String before = first.render(List.of("rules"), query).block();
        assertTrue(before.contains("appeals"), before);
        Files.writeString(dir.resolve("rules.md"), """
                # Rules

                Ships only.
                """);
        KnowledgeBase second = KnowledgeBase.load(dir, 6000, 4000, settings, false, new ArrayList<>(), logger, List.of());
        assertEquals(before, first.render(List.of("rules"), query).block());
        assertNotEquals(before, second.render(List.of("rules"), query).block());
        assertFalse(second.render(List.of("rules"), query).block().contains("appeals"));
    }

    @Test
    void indexOfTwoHundredKilobytesBuildsAndSelectsQuickly() {
        StringBuilder body = new StringBuilder(220_000);
        for (int i = 0; i < 800; i++) {
            body.append("# Section ").append(i).append("\n\n");
            body.append("Paragraph ").append(i).append(" talks about docks and ships and fees. ");
            body.append("Extra words grow the file without matching the query.\n\n");
        }
        body.append("# Appeals\n\n");
        body.append("<!-- keywords: апелляция, бан -->\n");
        body.append("Игрок может подать апелляцию на бан через персонал.\n");
        while (body.length() < 200_000) {
            body.append("\n\nFiller line about the harbor weather and tides.");
        }
        Tokenizer tokenizer = Tokenizer.builtin(List.of());
        Map<String, String> files = Map.of("bulk", body.toString());
        for (int warm = 0; warm < 3; warm++) {
            KnowledgeIndex.build(files, tokenizer);
        }
        long bestBuild = Long.MAX_VALUE;
        KnowledgeIndex index = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            long buildStart = System.nanoTime();
            index = KnowledgeIndex.build(files, tokenizer);
            bestBuild = Math.min(bestBuild, System.nanoTime() - buildStart);
        }
        assertTrue(bestBuild < 200_000_000L, "fastest index build took " + (bestBuild / 1_000_000L) + " ms");
        assertTrue(index.paragraphCount() > 100);

        KeywordSelector selector = new KeywordSelector(
                index, tokenizer, KeywordSettings.defaults(), 6000, Logger.getLogger("knowledge-speed"));
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            selector.select(List.of("bulk"), "как подать апелляцию на бан " + i, List.of());
            best = Math.min(best, System.nanoTime() - start);
        }
        assertTrue(best < 1_000_000L, "fastest select took " + best + " ns");
        KeywordSelector.Selection chosen = selector.select(List.of("bulk"), "как подать апелляцию на бан", List.of());
        assertTrue(chosen.content().contains("апелляцию"), chosen.content());
    }

    @Test
    void cjkParagraphIsFoundByABigram() {
        KnowledgeIndex index = KnowledgeIndex.build(Map.of("rules", "服务器规则很重要"), Tokenizer.builtin(List.of()));
        KeywordSelector selector = new KeywordSelector(
                index, Tokenizer.builtin(List.of()), KeywordSettings.defaults(), 6000, Logger.getLogger("cjk"));
        KeywordSelector.Selection chosen = selector.select(List.of("rules"), "规则", List.of());
        assertTrue(chosen.content().contains("服务器规则很重要"), chosen.content());
    }
}
