package io.github.neareststep.nexusai.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictParserTest {

    @Test
    void readsABareJsonObject() {
        ModerationVerdict verdict = VerdictParser.parse(
                "{\"flagged\":true,\"category\":\"toxicity\",\"reason\":\"slur\"}").orElseThrow();
        assertTrue(verdict.flagged());
        assertEquals("toxicity", verdict.category());
        assertEquals("slur", verdict.reason());
    }

    @Test
    void readsAFencedObjectAndCategoryAliases() {
        ModerationVerdict verdict = VerdictParser.parse("""
                ```json
                {"flagged": true, "category": "veiled_insult", "reason": "backhanded"}
                ```
                """).orElseThrow();
        assertTrue(verdict.flagged());
        assertEquals("veiled insult", verdict.category());
        assertEquals("backhanded", verdict.reason());
    }

    @Test
    void readsTheFirstObjectInsideProse() {
        ModerationVerdict verdict = VerdictParser.parse(
                "Verdict follows: {\"flagged\":false,\"category\":\"none\",\"reason\":\"\"} thanks").orElseThrow();
        assertFalse(verdict.flagged());
        assertEquals("none", verdict.category());
        assertEquals("", verdict.reason());
    }

    @Test
    void acceptsStringAndNumericFlags() {
        ModerationVerdict asText = VerdictParser.parse(
                "{\"flagged\":\"true\",\"category\":\"harassment\",\"reason\":\"repeated\"}").orElseThrow();
        assertTrue(asText.flagged());
        assertEquals("harassment", asText.category());

        ModerationVerdict asNumber = VerdictParser.parse(
                "{\"flagged\":0,\"category\":\"spam\",\"reason\":\"ignored because it is clear\"}").orElseThrow();
        assertFalse(asNumber.flagged());
        assertEquals("", asNumber.reason());
    }

    @Test
    void collapsesReasonWhitespaceAndMapsUnknownCategories() {
        ModerationVerdict verdict = VerdictParser.parse(
                "{\"flagged\":true,\"category\":\"other-thing\",\"reason\":\"too   many\\nlines\"}").orElseThrow();
        assertEquals("other", verdict.category());
        assertEquals("too many lines", verdict.reason());
    }

    @Test
    void unparseableRepliesAreEmpty() {
        assertTrue(VerdictParser.parse(null).isEmpty());
        assertTrue(VerdictParser.parse("").isEmpty());
        assertTrue(VerdictParser.parse("I will not classify this").isEmpty());
        assertTrue(VerdictParser.parse("{\"category\":\"spam\"}").isEmpty());
        assertTrue(VerdictParser.parse("{\"flagged\":\"maybe\"}").isEmpty());
        assertTrue(VerdictParser.parse("[]").isEmpty());
    }
}
