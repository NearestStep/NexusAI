package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.neareststep.nexusai.ai.ResponseUsage;

/**
 * Reads an OpenAI {@code usage} object. Unknown fields are ignored. A missing object,
 * null, or a non-object is {@link ResponseUsage#none()}.
 */
public final class UsageJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UsageJson() {
    }

    /**
     * {@code usage} inside a JSON document. HTML, non-JSON, and a missing object are
     * {@link ResponseUsage#none()}. An empty object is a reported zero count.
     */
    public static ResponseUsage fromDocument(String body) {
        if (body == null || body.isBlank()) {
            return ResponseUsage.none();
        }
        String head = body.length() > 64 ? body.substring(0, 64) : body;
        String probe = head.stripLeading().toLowerCase(java.util.Locale.ROOT);
        if (probe.startsWith("<")) {
            return ResponseUsage.none();
        }
        try {
            JsonNode tree = MAPPER.readTree(body);
            if (tree == null || !tree.isObject()) {
                return ResponseUsage.none();
            }
            return read(tree.get("usage"));
        } catch (Exception ignored) {
            return ResponseUsage.none();
        }
    }

    public static ResponseUsage read(JsonNode usage) {
        if (usage == null || usage.isNull() || usage.isMissingNode() || !usage.isObject()) {
            return ResponseUsage.none();
        }
        int prompt = number(usage.get("prompt_tokens"));
        int completion = number(usage.get("completion_tokens"));
        Integer total = usage.has("total_tokens") && !usage.get("total_tokens").isNull()
                ? number(usage.get("total_tokens"))
                : null;
        Double cost = cost(usage.get("cost"));
        return ResponseUsage.reported(prompt, completion, total, cost);
    }

    /**
     * A missing or non-numeric field is zero. Negatives are zero. A value above
     * {@link Integer#MAX_VALUE} is clamped there.
     */
    private static int number(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isNumber()) {
            return 0;
        }
        double raw = node.asDouble();
        if (!Double.isFinite(raw) || raw <= 0d) {
            return 0;
        }
        if (raw >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) raw;
    }

    private static Double cost(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isNumber()) {
            return null;
        }
        double raw = node.asDouble();
        if (!Double.isFinite(raw)) {
            return null;
        }
        return raw;
    }
}
