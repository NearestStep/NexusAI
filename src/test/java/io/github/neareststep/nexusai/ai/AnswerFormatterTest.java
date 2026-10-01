package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerFormatterTest {

    @Test
    void stripsCommonMarkdown() {
        String raw = """
                # Title
                **Hello** _there_
                See [docs](https://example.com) and `code`.
                ```
                fence
                ```
                ~~old~~
                """;
        String formatted = AnswerFormatter.format(raw, true, 0, 0);
        assertEquals("Title\nHello there\nSee docs and code.\nfence\n\nold", formatted.replace("\r\n", "\n"));
    }

    @Test
    void limitsLinesAndCharacters() {
        String raw = "one\ntwo\nthree";
        assertEquals("one\ntwo", AnswerFormatter.format(raw, false, 0, 2));
        assertEquals("Hello…", AnswerFormatter.format("Hello world", false, 6, 0));
    }

    @Test
    void stripsSectionSignsBeforePlaceholdersDialoguePoolAndCache() {
        assertEquals("In the plains biome", AnswerFormatter.format("In the §plains§ biome", false, 0, 0));
        assertEquals("Hello", AnswerFormatter.format("§cHello", false, 0, 0));
        assertEquals("Hello", AnswerFormatter.format("§c§lHello", false, 0, 0));
        assertEquals("hex", AnswerFormatter.format("§x§f§f§0§0§0§0hex", false, 0, 0));
        assertEquals("A", AnswerFormatter.format("A&B", false, 0, 0));
        assertEquals("rock & stone", AnswerFormatter.format("rock & stone", false, 0, 0));
        assertEquals("AMPRED AMPHASH AMPHEX AMPBOLD", AnswerFormatter.format(
                "&cAMPRED &#FF0000AMPHASH &x&f&f&0&0&0&0AMPHEX &lAMPBOLD", false, 0, 0));
        assertEquals("AMPRED &#FF0000AMPHASH AMPHEX AMPBOLD", AnswerFormatter.format(
                "&cAMPRED &#FF0000AMPHASH &x&f&f&0&0&0&0AMPHEX &lAMPBOLD", false, 0, 0, true));
        assertFalse(AnswerFormatter.format("§ksecret", false, 0, 0).contains("§"));
        assertFalse(AnswerFormatter.format("&ksecret", false, 0, 0).contains("&k"));
        assertEquals("Hello", PlayerInput.stripSectionSigns("§cHello"));
        assertEquals("Hello", PlayerInput.stripSectionSigns("&cHello"));
        assertEquals("Hello &&& END &&& traveler", AnswerFormatter.format("Hello &&&END&&& traveler", false, 0, 0));
    }

    @Test
    void keepsMarkdownWhenDisabled() {
        assertEquals("**Hi**", AnswerFormatter.format("**Hi**", false, 0, 0));
    }

    @Test
    void stripsListMarkersAndQuotesThenAppliesLimits() {
        String raw = """
                # Title

                Hello **bold** and *italic* and ~~gone~~.
                See [docs](https://example.com) and ![pic](https://example.com/a.png).
                Inline `code` and a fence:
                ```
                System.out.println(1);
                ```

                - first item
                > quoted line
                tail
                """;
        String formatted = AnswerFormatter.format(raw, true, 0, 0);
        assertFalse(formatted.contains("- "));
        assertFalse(formatted.contains("> "));
        assertFalse(formatted.contains("**"));
        assertFalse(formatted.contains("https://"));
        assertFalse(formatted.contains("```"));
        assertTrue(formatted.contains("first item"));
        assertTrue(formatted.contains("quoted line"));
        assertTrue(formatted.contains("bold"));
        assertTrue(formatted.contains("System.out.println(1)"));
        assertEquals("Title", AnswerFormatter.format(raw, true, 0, 1).split("\\R", -1)[0]);
        assertTrue(AnswerFormatter.format(raw, true, 8, 0).endsWith("…"));
    }
}
