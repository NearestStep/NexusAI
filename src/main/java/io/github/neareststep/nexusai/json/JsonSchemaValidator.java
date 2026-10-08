package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.api.JsonSchema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks one JSON value against a {@link JsonSchema}. At most {@link #MAX_ERRORS} lines are kept.
 * Each line is {@code $.path: reason} and does not repeat the value.
 */
public final class JsonSchemaValidator {

    public static final int MAX_ERRORS = 10;

    private JsonSchemaValidator() {
    }

    public static List<String> validate(JsonSchema schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        if (schema == null) {
            errors.add("$: schema is missing");
            return errors;
        }
        JsonNode definition;
        try {
            definition = JsonMappers.MAPPER.readTree(schema.json());
        } catch (Exception ex) {
            errors.add("$: schema is not JSON");
            return errors;
        }
        check(definition, value, "$", errors);
        return errors;
    }

    /**
     * {@code minLength} only, after string values have been shortened.
     * Other keywords are not checked again.
     */
    public static List<String> minLengths(JsonSchema schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        if (schema == null || value == null) {
            return errors;
        }
        JsonNode definition;
        try {
            definition = JsonMappers.MAPPER.readTree(schema.json());
        } catch (Exception ex) {
            return errors;
        }
        minLength(definition, value, "$", errors);
        return errors;
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (errors.size() >= MAX_ERRORS || schema == null || !schema.isObject()) {
            return;
        }
        if (schema.has("enum") && !enumMatches(schema.get("enum"), value)) {
            add(errors, path + ": value is not in enum");
            return;
        }
        JsonNode type = schema.get("type");
        if (type != null && !typeMatches(type, value)) {
            add(errors, path + ": expected " + typeLabel(type) + ", got " + jsonType(value));
            return;
        }
        if (value != null && value.isTextual()) {
            int length = value.asText().codePointCount(0, value.asText().length());
            bound(schema, "minLength", length, path, "shorter than minLength ", errors);
            bound(schema, "maxLength", length, path, "longer than maxLength ", errors, true);
        }
        if (value != null && value.isNumber()) {
            BigDecimal number = value.decimalValue();
            if (schema.has("minimum") && number.compareTo(schema.get("minimum").decimalValue()) < 0) {
                add(errors, path + ": below minimum " + schema.get("minimum").decimalValue().toPlainString());
            }
            if (schema.has("maximum") && number.compareTo(schema.get("maximum").decimalValue()) > 0) {
                add(errors, path + ": above maximum " + schema.get("maximum").decimalValue().toPlainString());
            }
        }
        if (value != null && value.isArray()) {
            bound(schema, "minItems", value.size(), path, "fewer than minItems ", errors);
            bound(schema, "maxItems", value.size(), path, "more than maxItems ", errors, true);
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                for (int i = 0; i < value.size() && errors.size() < MAX_ERRORS; i++) {
                    check(items, value.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (value != null && value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode name : required) {
                    if (errors.size() >= MAX_ERRORS) {
                        return;
                    }
                    if (name.isTextual() && !value.has(name.asText())) {
                        add(errors, path + "." + name.asText() + ": required property is missing");
                    }
                }
            }
            JsonNode properties = schema.get("properties");
            boolean closed = schema.has("additionalProperties")
                    && schema.get("additionalProperties").isBoolean()
                    && !schema.get("additionalProperties").booleanValue();
            Set<String> known = new HashSet<>();
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(known::add);
            }
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (errors.size() >= MAX_ERRORS) {
                    return;
                }
                String child = path + "." + name;
                if (properties != null && properties.has(name)) {
                    check(properties.get(name), value.get(name), child, errors);
                } else if (closed && !known.contains(name)) {
                    add(errors, child + ": additional property is not allowed");
                }
            }
        }
    }

    private static void minLength(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (errors.size() >= MAX_ERRORS || schema == null || !schema.isObject() || value == null) {
            return;
        }
        if (value.isTextual() && schema.has("minLength")) {
            int length = value.asText().codePointCount(0, value.asText().length());
            int min = schema.get("minLength").intValue();
            if (length < min) {
                add(errors, path + ": shorter than minLength " + min);
            }
        }
        if (value.isArray() && schema.has("items") && schema.get("items").isObject()) {
            JsonNode items = schema.get("items");
            for (int i = 0; i < value.size() && errors.size() < MAX_ERRORS; i++) {
                minLength(items, value.get(i), path + "[" + i + "]", errors);
            }
        }
        if (value.isObject()) {
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                return;
            }
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (errors.size() >= MAX_ERRORS) {
                    return;
                }
                if (properties.has(name)) {
                    minLength(properties.get(name), value.get(name), path + "." + name, errors);
                }
            }
        }
    }

    private static void bound(
            JsonNode schema,
            String keyword,
            int actual,
            String path,
            String reason,
            List<String> errors
    ) {
        bound(schema, keyword, actual, path, reason, errors, false);
    }

    private static void bound(
            JsonNode schema,
            String keyword,
            int actual,
            String path,
            String reason,
            List<String> errors,
            boolean upper
    ) {
        if (!schema.has(keyword) || errors.size() >= MAX_ERRORS) {
            return;
        }
        int limit = schema.get(keyword).intValue();
        boolean violated = upper ? actual > limit : actual < limit;
        if (violated) {
            add(errors, path + ": " + reason + limit);
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode value) {
        if (type.isTextual()) {
            return matchesOne(type.asText(), value);
        }
        if (!type.isArray()) {
            return false;
        }
        for (JsonNode item : type) {
            if (item.isTextual() && matchesOne(item.asText(), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesOne(String type, JsonNode value) {
        if (value == null || value.isMissingNode()) {
            return false;
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> isInteger(value);
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static boolean isInteger(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return false;
        }
        return value.decimalValue().stripTrailingZeros().scale() <= 0;
    }

    private static boolean enumMatches(JsonNode options, JsonNode value) {
        if (options == null || !options.isArray()) {
            return true;
        }
        for (JsonNode option : options) {
            if (same(option, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(JsonNode option, JsonNode value) {
        if (option == null || value == null) {
            return false;
        }
        if (option.isNumber() && value.isNumber()) {
            return option.decimalValue().compareTo(value.decimalValue()) == 0;
        }
        if (option.isNull() && value.isNull()) {
            return true;
        }
        return option.equals(value);
    }

    private static String typeLabel(JsonNode type) {
        if (type.isTextual()) {
            return type.asText();
        }
        if (!type.isArray()) {
            return "value";
        }
        StringBuilder label = new StringBuilder();
        for (JsonNode item : type) {
            if (!item.isTextual()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(" or ");
            }
            label.append(item.asText());
        }
        return label.isEmpty() ? "value" : label.toString();
    }

    static String jsonType(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        if (isInteger(value)) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        return "value";
    }

    private static void add(List<String> errors, String line) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(line);
        }
    }
}
