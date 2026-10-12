package io.github.neareststep.nexusai.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.ApiStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A JSON Schema from the subset NexusAI can send and check.
 * <p>
 * {@link #parse(String)} rejects anything outside that subset. The schema is not
 * applied until {@link NexusAIApi#generateJson}. Values the model returns are not safe
 * to place in a command, a name, or a path until the caller checks them again.
 */
public final class JsonSchema {

    /** UTF-8 bytes. A longer document is rejected and is not sent. */
    public static final int MAX_BYTES = 16 * 1024;

    /** Root counts as 1. A property schema or {@code items} schema is one level deeper. */
    public static final int MAX_DEPTH = 5;

    /** Keys under every {@code properties} object, including nested ones. */
    public static final int MAX_PROPERTIES = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "items", "enum",
            "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems",
            "description", "title");

    private static final Set<String> TYPES = Set.of(
            "object", "array", "string", "integer", "number", "boolean", "null");

    private final String json;
    private final String hash;
    private final boolean strict;

    private JsonSchema(String json, String hash, boolean strict) {
        this.json = json;
        this.hash = hash;
        this.strict = strict;
    }

    /**
     * Parses one schema from the supported subset.
     * The root must be {@code type: object}. Depth, property count, and size are capped.
     *
     * @throws IllegalArgumentException when {@code schemaJson} is outside the subset, with the path
     */
    public static JsonSchema parse(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("$: schema is empty");
        }
        if (schemaJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("$: schema is longer than 16 KiB");
        }
        JsonNode root = read(schemaJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("$: schema must be an object");
        }
        Walk walk = new Walk();
        walk.schema(root, "$", 1, true);
        if (walk.properties > MAX_PROPERTIES) {
            throw new IllegalArgumentException("$: more than 100 properties");
        }
        String normalized = write(sort(root));
        return new JsonSchema(normalized, hash(normalized), walk.strict);
    }

    /** Normalized schema text. Key order is stable. Whitespace is not significant. */
    public String json() {
        return json;
    }

    /** First 16 hex characters of the SHA-256 of {@link #json()}. */
    public String hash() {
        return hash;
    }

    /**
     * True when every object schema sets {@code additionalProperties} to false and lists
     * every property in {@code required}. OpenAI strict mode needs this.
     */
    @ApiStatus.Internal
    public boolean strict() {
        return strict;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonSchema schema)) {
            return false;
        }
        return json.equals(schema.json);
    }

    @Override
    public int hashCode() {
        return json.hashCode();
    }

    @Override
    public String toString() {
        return json;
    }

    private static JsonNode read(String schemaJson) {
        try {
            return MAPPER.readTree(schemaJson);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("duplicate")) {
                throw new IllegalArgumentException("$: duplicate key");
            }
            throw new IllegalArgumentException("$: schema is not JSON");
        }
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalArgumentException("$: schema is not JSON");
        }
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode child : node) {
                array.add(sort(child));
            }
            return array;
        }
        ObjectNode object = MAPPER.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        for (String name : names) {
            object.set(name, sort(node.get(name)));
        }
        return object;
    }

    private static final class Walk {
        private int properties;
        private boolean strict = true;

        private void schema(JsonNode node, String path, int depth, boolean root) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException(path + ": nested deeper than 5");
            }
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException(path + ": expected a schema object");
            }
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!KEYWORDS.contains(name)) {
                    throw new IllegalArgumentException(path + "." + name + ": unsupported keyword");
                }
            }
            JsonNode type = node.get("type");
            if (root && !isExactObject(type)) {
                throw new IllegalArgumentException("$: root type must be object");
            }
            if (type != null) {
                types(type, path + ".type");
            }
            text(node.get("description"), path + ".description");
            text(node.get("title"), path + ".title");
            nonNegative(node.get("minLength"), path + ".minLength");
            nonNegative(node.get("maxLength"), path + ".maxLength");
            number(node.get("minimum"), path + ".minimum");
            number(node.get("maximum"), path + ".maximum");
            nonNegative(node.get("minItems"), path + ".minItems");
            nonNegative(node.get("maxItems"), path + ".maxItems");
            enumValues(node.get("enum"), path + ".enum");
            List<String> required = required(node.get("required"), path + ".required");
            JsonNode propertiesNode = node.get("properties");
            List<String> propertyNames = List.of();
            if (propertiesNode != null) {
                propertyNames = properties(propertiesNode, path, depth);
            }
            additional(node.get("additionalProperties"), path + ".additionalProperties");
            JsonNode items = node.get("items");
            if (items != null) {
                if (!items.isObject()) {
                    throw new IllegalArgumentException(path + ".items: expected one schema");
                }
                schema(items, path + ".items", depth + 1, false);
            }
            if (describesObject(type, propertiesNode != null)) {
                boolean closed = node.has("additionalProperties")
                        && node.get("additionalProperties").isBoolean()
                        && !node.get("additionalProperties").booleanValue();
                boolean listed = required.containsAll(propertyNames);
                if (!closed || !listed) {
                    strict = false;
                }
            }
        }

        private List<String> properties(JsonNode node, String path, int depth) {
            if (!node.isObject()) {
                throw new IllegalArgumentException(path + ".properties: expected an object");
            }
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            properties += names.size();
            if (properties > MAX_PROPERTIES) {
                throw new IllegalArgumentException("$: more than 100 properties");
            }
            for (String name : names) {
                schema(node.get(name), path + ".properties." + name, depth + 1, false);
            }
            return names;
        }

        private static boolean isExactObject(JsonNode type) {
            return type != null && type.isTextual() && "object".equals(type.asText());
        }

        private static boolean describesObject(JsonNode type, boolean hasProperties) {
            if (type == null) {
                return hasProperties;
            }
            if (type.isTextual()) {
                return "object".equals(type.asText());
            }
            if (!type.isArray()) {
                return false;
            }
            for (JsonNode item : type) {
                if (item.isTextual() && "object".equals(item.asText())) {
                    return true;
                }
            }
            return false;
        }

        private static void types(JsonNode type, String path) {
            if (type.isTextual()) {
                if (!TYPES.contains(type.asText())) {
                    throw new IllegalArgumentException(path + ": unsupported type");
                }
                return;
            }
            if (!type.isArray() || type.isEmpty()) {
                throw new IllegalArgumentException(path + ": unsupported type");
            }
            for (int i = 0; i < type.size(); i++) {
                JsonNode item = type.get(i);
                if (!item.isTextual() || !TYPES.contains(item.asText())) {
                    throw new IllegalArgumentException(path + "[" + i + "]: unsupported type");
                }
            }
        }

        private static void text(JsonNode node, String path) {
            if (node != null && !node.isTextual()) {
                throw new IllegalArgumentException(path + ": expected a string");
            }
        }

        private static void nonNegative(JsonNode node, String path) {
            if (node == null) {
                return;
            }
            if (!node.isIntegralNumber() || node.decimalValue().signum() < 0) {
                throw new IllegalArgumentException(path + ": must be a non-negative integer");
            }
            if (node.decimalValue().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException(path + ": must be a non-negative integer");
            }
        }

        private static void number(JsonNode node, String path) {
            if (node != null && !node.isNumber()) {
                throw new IllegalArgumentException(path + ": expected a number");
            }
        }

        private static void enumValues(JsonNode node, String path) {
            if (node == null) {
                return;
            }
            if (!node.isArray()) {
                throw new IllegalArgumentException(path + ": expected an array");
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                if (item.isObject() || item.isArray()) {
                    throw new IllegalArgumentException(path + "[" + i + "]: unsupported enum value");
                }
            }
        }

        private static void additional(JsonNode node, String path) {
            if (node == null) {
                return;
            }
            if (!node.isBoolean()) {
                throw new IllegalArgumentException(path + ": expected true or false");
            }
        }

        private static List<String> required(JsonNode node, String path) {
            if (node == null) {
                return List.of();
            }
            if (!node.isArray()) {
                throw new IllegalArgumentException(path + ": expected an array");
            }
            List<String> names = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                if (!item.isTextual()) {
                    throw new IllegalArgumentException(path + "[" + i + "]: expected a string");
                }
                names.add(item.asText());
            }
            return names;
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
