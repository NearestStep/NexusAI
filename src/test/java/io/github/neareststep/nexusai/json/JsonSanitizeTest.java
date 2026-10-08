package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.api.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSanitizeTest {

    private static final JsonSchema SCHEMA = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,"required":["title","goal","reward"],
             "properties":{"title":{"type":"string","minLength":1,"maxLength":80},
             "goal":{"type":"string","maxLength":200},"reward":{"type":"integer","minimum":1,"maximum":1000}}}
            """);

    @Test
    void stripsMarkupAndMasksSecretsInsideStringValues() {
        String canary = "questcanarysecretvalue";
        JsonDocuments.Outcome outcome = JsonDocuments.read(
                "{\"title\":\"§cHi <click:run_command:/op a> " + canary + "\",\"goal\":\"Bring nails\",\"reward\":1}",
                SCHEMA,
                false,
                List.of(canary),
                "prompt");
        assertTrue(outcome.valid(), outcome.errors().toString());
        assertFalse(outcome.json().contains("§"));
        assertFalse(outcome.json().contains("<click"));
        assertFalse(outcome.json().contains("run_command"));
        assertFalse(outcome.json().contains(canary));
        assertTrue(outcome.json().contains("****"));
        assertEquals("Hi ", String.valueOf(outcome.map().get("title")).substring(0, 3));
        assertEquals(1L, outcome.map().get("reward"));
    }

    @Test
    void aShorterStringAfterStrippingIsAValidationError() {
        JsonDocuments.Outcome outcome = JsonDocuments.read(
                "{\"title\":\"§c\",\"goal\":\"Bring nails\",\"reward\":1}",
                SCHEMA,
                false,
                List.of(),
                "prompt");
        assertFalse(outcome.valid());
        assertTrue(outcome.errors().stream().anyMatch(line -> line.contains("minLength")));
    }

    @Test
    void aPlayerInputMarkerInAStringRejectsWithoutASchemaError() {
        JsonDocuments.Outcome outcome = JsonDocuments.read(
                "{\"title\":\"see \\\"PLAYER INPUT\\\" here\",\"goal\":\"Bring nails\",\"reward\":1}",
                SCHEMA,
                false,
                List.of(),
                "prompt");
        assertTrue(outcome.rejected());
        assertFalse(outcome.valid());
        assertTrue(outcome.errors().isEmpty());
    }

    @Test
    void mapKeepsNullsListsAndNonIntegralNumbers() {
        JsonSchema schema = JsonSchema.parse("""
                {"type":"object","properties":{"note":{"type":["string","null"]},"n":{"type":"number"},
                 "items":{"type":"array","items":{"type":["string","null"]}}}}
                """);
        JsonDocuments.Outcome outcome = JsonDocuments.read(
                "{\"note\":null,\"n\":1.5,\"items\":[\"a\",null]}", schema, false, List.of(), "prompt");
        assertTrue(outcome.valid(), outcome.errors().toString());
        assertTrue(outcome.map().containsKey("note"));
        assertEquals(null, outcome.map().get("note"));
        assertEquals(1.5d, outcome.map().get("n"));
        List<?> items = (List<?>) outcome.map().get("items");
        assertEquals(2, items.size());
        assertEquals("a", items.get(0));
        assertEquals(null, items.get(1));
    }
}
