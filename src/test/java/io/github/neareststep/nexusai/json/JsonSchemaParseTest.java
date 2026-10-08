package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.api.JsonSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaParseTest {

    @Test
    void normalizesKeyOrderAndHashesTheFirst16Hex() throws Exception {
        JsonSchema spaced = JsonSchema.parse("""
                { "type" : "object", "properties" : { "b" : { "type" : "string" }, "a" : { "type" : "number" } } }
                """);
        JsonSchema compact = JsonSchema.parse(
                "{\"properties\":{\"b\":{\"type\":\"string\"},\"a\":{\"type\":\"number\"}},\"type\":\"object\"}");
        assertEquals(spaced.json(), compact.json());
        assertEquals(spaced.hash(), compact.hash());
        assertEquals(16, spaced.hash().length());
        assertTrue(spaced.json().indexOf("\"a\"") < spaced.json().indexOf("\"b\""));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(spaced.json().getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(digest, 0, 8), spaced.hash());
    }

    @Test
    void keepsDescriptionAndTitleAndAllowsATypeUnion() {
        JsonSchema schema = JsonSchema.parse("""
                {"title":"Quest","type":"object","properties":{"note":{"type":["string","null"],"description":"optional"}}}
                """);
        assertTrue(schema.json().contains("\"description\":\"optional\""));
        assertTrue(schema.json().contains("\"title\":\"Quest\""));
        assertFalse(schema.strict());
    }

    @Test
    void strictIsTrueOnlyWhenEveryObjectIsClosedAndFullyRequired() {
        JsonSchema closed = JsonSchema.parse("""
                {"type":"object","additionalProperties":false,"required":["title"],
                 "properties":{"title":{"type":"string"}}}
                """);
        JsonSchema open = JsonSchema.parse("""
                {"type":"object","additionalProperties":true,"required":["title"],
                 "properties":{"title":{"type":"string"}}}
                """);
        JsonSchema missingRequired = JsonSchema.parse("""
                {"type":"object","additionalProperties":false,"properties":{"title":{"type":"string"}}}
                """);
        assertTrue(closed.strict());
        assertFalse(open.strict());
        assertFalse(missingRequired.strict());
    }

    @Test
    void rejectsARootThatIsNotAnObject() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"));
        assertEquals("$: root type must be object", error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> JsonSchema.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> JsonSchema.parse(""));
    }

    @Test
    void rejectsUnsupportedKeywordsWithAPathAndDoesNotEchoTheValue() {
        String canary = "schema-canary-value-should-not-leak";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("{\"type\":\"object\",\"$ref\":\"" + canary + "\"}"));
        assertEquals("$.$ref: unsupported keyword", error.getMessage());
        assertFalse(error.getMessage().contains(canary));
        IllegalArgumentException nested = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("""
                        {"type":"object","properties":{"a":{"type":"string","pattern":"secret-pattern"}}}
                        """));
        assertEquals("$.properties.a.pattern: unsupported keyword", nested.getMessage());
        assertFalse(nested.getMessage().contains("secret-pattern"));
    }

    @Test
    void rejectsDuplicateKeysOneOfAndASchemaObjectForAdditionalProperties() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("{\"type\":\"object\",\"type\":\"array\"}"));
        assertEquals("$: duplicate key", duplicate.getMessage());
        IllegalArgumentException oneOf = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("{\"type\":\"object\",\"oneOf\":[]}"));
        assertTrue(oneOf.getMessage().contains("oneOf"));
        IllegalArgumentException additional = assertThrows(IllegalArgumentException.class,
                () -> JsonSchema.parse("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\"}}"));
        assertEquals("$.additionalProperties: expected true or false", additional.getMessage());
    }

    @Test
    void capsDepthPropertiesAndBytes() {
        String deep = "{\"type\":\"object\",\"properties\":{\"a\":" + nested(5) + "}}";
        IllegalArgumentException depth = assertThrows(IllegalArgumentException.class, () -> JsonSchema.parse(deep));
        assertTrue(depth.getMessage().contains("deeper than 5"));

        StringBuilder properties = new StringBuilder("{\"type\":\"object\",\"properties\":{");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append("\"p").append(i).append("\":{\"type\":\"string\"}");
        }
        properties.append("}}");
        IllegalArgumentException count = assertThrows(IllegalArgumentException.class, () -> JsonSchema.parse(properties.toString()));
        assertEquals("$: more than 100 properties", count.getMessage());

        String huge = "{\"type\":\"object\",\"description\":\"" + "x".repeat(JsonSchema.MAX_BYTES) + "\"}";
        IllegalArgumentException size = assertThrows(IllegalArgumentException.class, () -> JsonSchema.parse(huge));
        assertEquals("$: schema is longer than 16 KiB", size.getMessage());
        assertFalse(size.getMessage().contains("xxxx"));
    }

    private static String nested(int levels) {
        if (levels == 0) {
            return "{\"type\":\"string\"}";
        }
        return "{\"type\":\"object\",\"properties\":{\"n\":" + nested(levels - 1) + "}}";
    }
}
