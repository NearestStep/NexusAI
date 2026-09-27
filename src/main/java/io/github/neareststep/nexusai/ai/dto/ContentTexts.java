package io.github.neareststep.nexusai.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads chat-completion {@code content}, which providers send either as a string or as an array of parts.
 */
public final class ContentTexts {

    private ContentTexts() {
    }

    public static String read(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode part : node) {
                appendPart(joined, part);
            }
            return joined.toString();
        }
        if (node.isObject()) {
            StringBuilder joined = new StringBuilder();
            appendPart(joined, node);
            return joined.toString();
        }
        return node.asText(null);
    }

    private static void appendPart(StringBuilder joined, JsonNode part) {
        if (part == null || part.isNull()) {
            return;
        }
        if (part.isTextual()) {
            joined.append(part.asText());
            return;
        }
        if (!part.isObject()) {
            return;
        }
        if (part.hasNonNull("text")) {
            joined.append(part.get("text").asText());
            return;
        }
        if (part.hasNonNull("content") && part.get("content").isTextual()) {
            joined.append(part.get("content").asText());
        }
    }
}
