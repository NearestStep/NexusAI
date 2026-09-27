package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void keepsMarkdownWhenDisabled() {
        assertEquals("**Hi**", AnswerFormatter.format("**Hi**", false, 0, 0));
    }
}
