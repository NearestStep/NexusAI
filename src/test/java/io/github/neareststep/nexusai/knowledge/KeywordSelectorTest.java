package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.knowledge.KeywordSettings.OnNoMatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordSelectorTest {

    @Test
    void weightAndIdfAndPrefixDecideTheRanking() {
        String rules = """
                plain alpha here

                # alpha

                this paragraph inherits the heading

                <!-- keywords: alpha -->
                explicit alpha paragraph

                # Forms

                Игроки читают правилам сервера.

                teleporting home is allowed
                """;
        String common = """
                common filler one

                common filler two

                common filler three

                rare common together
                """;
        KeywordSelector selector = selector(Map.of("rules", rules, "notes", common), settings(1, 1200, 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection weighted = selector.select(List.of("rules"), "alpha", List.of());
        assertTrue(weighted.content().contains("explicit alpha paragraph"), weighted.content());
        assertFalse(weighted.content().contains("plain alpha"));
        assertTrue(weighted.labels().contains("rules#3"), weighted.labels().toString());

        KeywordSelector.Selection heading = selector(Map.of("rules", """
                plain alpha here

                # alpha

                this paragraph inherits the heading
                """), settings(1, 1200, 1, OnNoMatch.NONE), 6000).select(List.of("rules"), "alpha", List.of());
        assertTrue(heading.content().contains("# alpha"), heading.content());
        assertFalse(heading.content().contains("plain alpha"));

        KeywordSelector.Selection rare = selector.select(List.of("notes"), "rare common", List.of());
        assertTrue(rare.content().startsWith("[notes]\n"), rare.content());
        assertTrue(rare.content().contains("rare common together"), rare.content());
        assertFalse(rare.content().contains("filler one"));

        KeywordSelector prefixSelector = selector(
                Map.of("rules", rules), settings(6, 1200, 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection prefix = prefixSelector.select(List.of("rules"), "правила teleport", List.of());
        assertTrue(prefix.content().contains("правилам"), prefix.content());
        assertTrue(prefix.content().contains("teleporting"), prefix.content());
    }

    @Test
    void documentOrderLimitsAndNoMatch() {
        String rules = """
                zeta marker

                alpha marker
                """;
        String faq = """
                # Help

                first faq paragraph

                second faq paragraph
                """;
        KeywordSelector both = selector(Map.of("rules", rules, "faq", faq), settings(6, 1200, 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection ordered = both.select(List.of("faq", "rules"), "marker faq", List.of());
        assertTrue(ordered.content().indexOf("[faq]") < ordered.content().indexOf("[rules]"), ordered.content());
        assertTrue(ordered.content().indexOf("zeta marker") < ordered.content().indexOf("alpha marker"), ordered.content());
        assertTrue(ordered.content().contains("# Help\nfirst faq paragraph"), ordered.content());

        KeywordSelector one = selector(Map.of("rules", rules), settings(1, 1200, 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection earlier = one.select(List.of("rules"), "marker", List.of());
        assertTrue(earlier.content().contains("zeta marker"));
        assertFalse(earlier.content().contains("alpha marker"));

        KeywordSelector cut = selector(Map.of("rules", "One. Two continues past the limit."), settings(6, "One. Tw".length(), 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection limited = cut.select(List.of("rules"), "continues", List.of());
        assertEquals("[rules]\nOne.", limited.content());

        KeywordSelector none = selector(Map.of("rules", rules, "faq", faq), settings(1, 1200, 1, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection missed = none.select(List.of("rules", "faq"), "the and", List.of());
        assertEquals("", missed.content());
        assertTrue(missed.labels().isEmpty());

        KeywordSelector first = selector(Map.of("rules", rules, "faq", faq), settings(1, 1200, 1, OnNoMatch.FIRST), 6000);
        KeywordSelector.Selection fallback = first.select(List.of("rules", "faq"), "zzzz", List.of());
        assertTrue(fallback.content().contains("zeta marker"), fallback.content());
        assertTrue(fallback.content().contains("first faq paragraph"), fallback.content());
        assertFalse(fallback.content().contains("alpha marker"));
        assertFalse(fallback.content().contains("second faq paragraph"));

        KeywordSelector strict = selector(Map.of("rules", "alpha only\n\nalpha beta together"), settings(6, 1200, 2, OnNoMatch.NONE), 6000);
        KeywordSelector.Selection matches = strict.select(List.of("rules"), "alpha beta", List.of());
        assertTrue(matches.content().contains("alpha beta together"));
        assertFalse(matches.content().contains("alpha only"));
    }

    @Test
    void requestCapWarnsOnceAndCacheStaysBounded() {
        Logger logger = Logger.getLogger("keyword-cap");
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
        String paragraph = "alpha ".repeat(40);
        KeywordSelector selector = selector(Map.of("rules", paragraph), settings(6, 1200, 1, OnNoMatch.NONE), 30, logger);
        KeywordSelector.Selection first = selector.select(List.of("rules"), "alpha", List.of());
        KeywordSelector.Selection second = selector.select(List.of("rules"), "alpha", List.of());
        assertEquals(first.content(), second.content());
        assertTrue(first.truncated());
        assertEquals(30, first.content().length());
        assertEquals(1, logged.size(), logged.toString());
        assertTrue(logged.getFirst().contains("truncated to 30"));

        for (int i = 0; i < KeywordSelector.CACHE_CAPACITY + 5; i++) {
            selector.select(List.of("rules"), "alpha extra" + i, List.of());
        }
        assertTrue(selector.cacheSize() <= KeywordSelector.CACHE_CAPACITY);
    }

    @Test
    void decimalPointIsNotASentenceEnd() {
        assertEquals("Version 1.2 is current.", KeywordSelector.limitParagraph("Version 1.2 is current. More text.", 24));
        assertEquals("Version", KeywordSelector.limitParagraph("Version 1.2 is current", 8));
    }

    private static KeywordSelector selector(Map<String, String> files, KeywordSettings settings, int maxChars) {
        return selector(files, settings, maxChars, Logger.getLogger("keyword-selector"));
    }

    private static KeywordSelector selector(Map<String, String> files, KeywordSettings settings, int maxChars, Logger logger) {
        Tokenizer tokenizer = Tokenizer.builtin(List.of());
        return new KeywordSelector(KnowledgeIndex.build(new LinkedHashMap<>(files), tokenizer), tokenizer, settings, maxChars, logger);
    }

    private static KeywordSettings settings(int paragraphs, int paragraphChars, int minMatches, OnNoMatch onNoMatch) {
        return new KeywordSettings(paragraphs, paragraphChars, 200_000, minMatches, onNoMatch, List.of());
    }
}
