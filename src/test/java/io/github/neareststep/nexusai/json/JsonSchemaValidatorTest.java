package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.api.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaValidatorTest {

    private static final JsonSchema QUEST = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,"required":["title","goal","reward"],
             "properties":{"title":{"type":"string","maxLength":40},"goal":{"type":"string","maxLength":200},
             "reward":{"type":"integer","minimum":1,"maximum":1000}}}
            """);

    @Test
    void acceptsTheQuestObject() {
        assertTrue(JsonSchemaValidator.validate(QUEST, read(
                "{\"title\":\"Iron nails\",\"goal\":\"Bring ten iron nails.\",\"reward\":12}")).isEmpty());
    }

    @Test
    void reportsTypeRequiredAdditionalAndBoundsWithoutTheValue() {
        List<String> errors = JsonSchemaValidator.validate(QUEST, read(
                "{\"title\":\"x\",\"goal\":\"y\",\"reward\":\"12\",\"extra\":true}"));
        assertTrue(errors.contains("$.reward: expected integer, got string"));
        assertTrue(errors.contains("$.extra: additional property is not allowed"));
        assertTrue(errors.stream().noneMatch(line -> line.contains("12") && line.contains("reward") && line.contains("string") && line.contains("\"12\"")));

        List<String> missing = JsonSchemaValidator.validate(QUEST, read("{\"title\":\"x\",\"goal\":\"y\"}"));
        assertEquals(List.of("$.reward: required property is missing"), missing);

        List<String> low = JsonSchemaValidator.validate(QUEST, read(
                "{\"title\":\"x\",\"goal\":\"y\",\"reward\":0}"));
        assertEquals(List.of("$.reward: below minimum 1"), low);
        List<String> high = JsonSchemaValidator.validate(QUEST, read(
                "{\"title\":\"x\",\"goal\":\"y\",\"reward\":1001}"));
        assertEquals(List.of("$.reward: above maximum 1000"), high);
    }

    @Test
    void checksUnionsEnumsArraysAndStopsAtTenErrors() {
        JsonSchema union = JsonSchema.parse("""
                {"type":"object","properties":{"note":{"type":["string","null"]},"flag":{"type":"boolean"},
                 "tag":{"enum":["a",1,true,null]},"items":{"type":"array","minItems":1,"maxItems":2,
                 "items":{"type":"integer"}}}}
                """);
        assertTrue(JsonSchemaValidator.validate(union, read("{\"note\":null,\"flag\":true,\"tag\":\"a\",\"items\":[1]}")).isEmpty());
        assertTrue(JsonSchemaValidator.validate(union, read("{\"tag\":1}")).isEmpty());
        assertTrue(JsonSchemaValidator.validate(union, read("{\"tag\":true}")).isEmpty());
        assertTrue(JsonSchemaValidator.validate(union, read("{\"tag\":null}")).isEmpty());
        assertEquals(List.of("$.tag: value is not in enum"),
                JsonSchemaValidator.validate(union, read("{\"tag\":\"z\"}")));
        assertEquals(List.of("$.note: expected string or null, got integer"),
                JsonSchemaValidator.validate(union, read("{\"note\":2}")));
        assertEquals(List.of("$.items: fewer than minItems 1"),
                JsonSchemaValidator.validate(union, read("{\"items\":[]}")));
        assertEquals(List.of("$.items: more than maxItems 2"),
                JsonSchemaValidator.validate(union, read("{\"items\":[1,2,3]}")));
        assertEquals(List.of("$.items[0]: expected integer, got string"),
                JsonSchemaValidator.validate(union, read("{\"items\":[\"a\"]}")));

        StringBuilder object = new StringBuilder("{\"type\":\"object\",\"required\":[");
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                object.append(',');
            }
            object.append("\"p").append(i).append('"');
        }
        object.append("]}");
        List<String> capped = JsonSchemaValidator.validate(JsonSchema.parse(object.toString()), read("{}"));
        assertEquals(JsonSchemaValidator.MAX_ERRORS, capped.size());
    }

    @Test
    void integerRejectsAFractionAndNumberAcceptsIt() {
        JsonSchema schema = JsonSchema.parse("""
                {"type":"object","properties":{"n":{"type":"number"},"i":{"type":"integer"}}}
                """);
        assertTrue(JsonSchemaValidator.validate(schema, read("{\"n\":1.5,\"i\":1.0}")).isEmpty());
        assertEquals(List.of("$.i: expected integer, got number"),
                JsonSchemaValidator.validate(schema, read("{\"i\":1.5}")));
    }

    @Test
    void minLengthAfterCleaningIsSeparateFromTheFirstPass() {
        JsonSchema schema = JsonSchema.parse("""
                {"type":"object","properties":{"title":{"type":"string","minLength":3}}}
                """);
        JsonNode cleaned = read("{\"title\":\"ab\"}");
        assertEquals(List.of("$.title: shorter than minLength 3"), JsonSchemaValidator.minLengths(schema, cleaned));
    }

    private static JsonNode read(String json) {
        try {
            return JsonMappers.MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
