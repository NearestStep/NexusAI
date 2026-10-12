package io.github.neareststep.nexusai.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonExtractorTest {

    @Test
    void stripsALeadingFenceAndFindsAnObjectInProse() {
        String fenced = """
                ```json
                {"title":"Iron nails","reward":12}
                ```
                """;
        assertEquals("{\"title\":\"Iron nails\",\"reward\":12}", JsonExtractor.extract(fenced));
        assertEquals("{\"title\":\"Iron nails\"}",
                JsonExtractor.extract("Sure, here you go: {\"title\":\"Iron nails\"} thanks"));
    }

    @Test
    void ignoresBracesInsideStringsAndHonorsEscapes() {
        assertEquals("{\"title\":\"a { b } c\",\"goal\":\"say \\\"hi\\\"\"}",
                JsonExtractor.extract("{\"title\":\"a { b } c\",\"goal\":\"say \\\"hi\\\"\"}"));
        assertEquals("{\"title\":\"brace } still inside\"}",
                JsonExtractor.extract("prefix {\"title\":\"brace } still inside\"} suffix"));
    }

    @Test
    void refusesAReplyLongerThan64KiBAndAnUnclosedObject() {
        String huge = "x".repeat(JsonExtractor.MAX_BYTES + 1);
        assertNull(JsonExtractor.extract(huge));
        assertTrue(JsonExtractor.tooLarge(huge));
        assertNull(JsonExtractor.extract("{\"title\":\"open\""));
        assertNull(JsonExtractor.extract("no object here"));
    }
}
