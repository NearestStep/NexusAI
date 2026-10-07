package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.ai.ResponseUsage;

/**
 * Reads an OpenAI {@code usage} object. Unknown fields are ignored. A missing object,
 * null, or a non-object is {@link ResponseUsage#none()}.
 */
public final class UsageJson {

    private UsageJson() {
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
