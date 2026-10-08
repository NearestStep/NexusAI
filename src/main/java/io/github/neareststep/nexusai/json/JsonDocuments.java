package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.config.SecretMask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one model reply into compact JSON: extract, parse, check, then clean string values.
 */
public final class JsonDocuments {

    private JsonDocuments() {
    }

    public static Outcome read(
            String raw,
            JsonSchema schema,
            boolean allowMarkup,
            Iterable<String> secrets,
            String prompt
    ) {
        if (JsonExtractor.tooLarge(raw)) {
            return Outcome.invalid(List.of("$: response is longer than 64 KiB"));
        }
        String extracted = JsonExtractor.extract(raw);
        if (extracted == null) {
            return Outcome.invalid(List.of("$: no JSON object"));
        }
        JsonNode tree;
        try {
            tree = JsonMappers.MAPPER.readTree(extracted);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("duplicate")) {
                return Outcome.invalid(List.of("$: duplicate key"));
            }
            return Outcome.invalid(List.of("$: JSON could not be parsed"));
        }
        if (tree == null || !tree.isObject()) {
            return Outcome.invalid(List.of("$: expected object, got " + JsonSchemaValidator.jsonType(tree)));
        }
        List<String> errors = JsonSchemaValidator.validate(schema, tree);
        if (!errors.isEmpty()) {
            return Outcome.invalid(errors);
        }
        JsonNode cleaned = sanitize(tree, allowMarkup, secrets);
        List<String> shortened = JsonSchemaValidator.minLengths(schema, cleaned);
        if (!shortened.isEmpty()) {
            return Outcome.invalid(shortened);
        }
        String joined = joinStrings(cleaned);
        String rejection = PlayerInput.rejectionReason(joined, prompt);
        if (rejection != null) {
            return Outcome.rejected(rejection);
        }
        try {
            String json = JsonMappers.MAPPER.writeValueAsString(cleaned);
            return Outcome.ok(json, toMap(cleaned));
        } catch (JsonProcessingException ex) {
            return Outcome.invalid(List.of("$: JSON could not be parsed"));
        }
    }

    /** True when {@code json} is one JSON object. */
    public static boolean isObject(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode tree = JsonMappers.MAPPER.readTree(json);
            return tree != null && tree.isObject();
        } catch (Exception ex) {
            return false;
        }
    }

    /** Compact JSON back into the map shape {@code generateJson} returns. Empty when it is not an object. */
    public static Map<String, Object> mapOf(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode tree = JsonMappers.MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) {
                return Map.of();
            }
            Object value = toValue(tree);
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
            return Map.of();
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static JsonNode sanitize(JsonNode node, boolean allowMarkup, Iterable<String> secrets) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            String stripped = PlayerInput.stripSectionSigns(node.asText(), allowMarkup);
            return JsonMappers.MAPPER.getNodeFactory().textNode(SecretMask.redact(stripped, secrets));
        }
        if (node.isArray()) {
            ArrayNode array = JsonMappers.MAPPER.createArrayNode();
            for (JsonNode child : node) {
                array.add(sanitize(child, allowMarkup, secrets));
            }
            return array;
        }
        if (node.isObject()) {
            ObjectNode object = JsonMappers.MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                object.set(name, sanitize(node.get(name), allowMarkup, secrets));
            }
            return object;
        }
        return node;
    }

    private static String joinStrings(JsonNode node) {
        StringBuilder joined = new StringBuilder();
        collect(node, joined);
        return joined.toString();
    }

    private static void collect(JsonNode node, StringBuilder joined) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            if (!joined.isEmpty()) {
                joined.append('\n');
            }
            joined.append(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collect(child, joined);
            }
            return;
        }
        if (node.isObject()) {
            node.forEach(child -> collect(child, joined));
        }
    }

    private static Map<String, Object> toMap(JsonNode node) {
        Object value = toValue(node);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private static Object toValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            if (node.decimalValue().stripTrailingZeros().scale() <= 0) {
                try {
                    return node.decimalValue().longValueExact();
                } catch (ArithmeticException ex) {
                    return node.doubleValue();
                }
            }
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(toValue(child));
            }
            return Collections.unmodifiableList(list);
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                map.put(name, toValue(node.get(name)));
            }
            return Collections.unmodifiableMap(map);
        }
        return null;
    }

    /** One read of a model reply. {@code json} and {@code map} are set only when {@code valid}. */
    public static final class Outcome {
        private final boolean valid;
        private final boolean rejected;
        private final String rejection;
        private final String json;
        private final Map<String, Object> map;
        private final List<String> errors;

        private Outcome(
                boolean valid,
                boolean rejected,
                String rejection,
                String json,
                Map<String, Object> map,
                List<String> errors
        ) {
            this.valid = valid;
            this.rejected = rejected;
            this.rejection = rejection == null ? "" : rejection;
            this.json = json;
            this.map = map == null ? Map.of() : map;
            this.errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static Outcome ok(String json, Map<String, Object> map) {
            return new Outcome(true, false, "", json, map, List.of());
        }

        static Outcome invalid(List<String> errors) {
            return new Outcome(false, false, "", null, Map.of(), errors);
        }

        static Outcome rejected(String rejection) {
            return new Outcome(false, true, rejection, null, Map.of(), List.of());
        }

        public boolean valid() {
            return valid;
        }

        public boolean rejected() {
            return rejected;
        }

        public String rejection() {
            return rejection;
        }

        public String json() {
            return json == null ? "" : json;
        }

        public Map<String, Object> map() {
            return map;
        }

        public List<String> errors() {
            return errors;
        }
    }
}
