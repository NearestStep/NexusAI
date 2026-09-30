package io.github.neareststep.nexusai.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a strict JSON verdict from a model reply. Surrounding prose and a markdown
 * fence are ignored. Anything that is not an object with a boolean {@code flagged}
 * field is empty, and the caller treats that as not flagged.
 */
public final class VerdictParser {

    static final Set<String> CATEGORIES = Set.of(
            "none", "toxicity", "insult", "veiled insult", "harassment", "spam");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_REASON = 240;

    private VerdictParser() {
    }

    public static Optional<ModerationVerdict> parse(String raw) {
        String json = extractObject(raw);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject() || !node.has("flagged")) {
                return Optional.empty();
            }
            Boolean flagged = booleanValue(node.get("flagged"));
            if (flagged == null) {
                return Optional.empty();
            }
            String category = category(node.get("category"), flagged);
            String reason = flagged ? reason(node.get("reason")) : "";
            return Optional.of(new ModerationVerdict(flagged, category, reason));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static String extractObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripFence(raw.trim());
        int start = -1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int newline = text.indexOf('\n');
        String body = newline >= 0 ? text.substring(newline + 1) : text.substring(3);
        int fence = body.lastIndexOf("```");
        if (fence >= 0) {
            body = body.substring(0, fence);
        }
        return body.trim();
    }

    private static Boolean booleanValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            int value = node.intValue();
            if (value == 1) {
                return true;
            }
            if (value == 0) {
                return false;
            }
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText().trim().toLowerCase(Locale.ROOT);
            return switch (text) {
                case "true", "yes" -> true;
                case "false", "no" -> false;
                default -> null;
            };
        }
        return null;
    }

    private static String category(JsonNode node, boolean flagged) {
        if (node == null || !node.isTextual()) {
            return flagged ? "other" : "none";
        }
        String normalized = node.asText().trim().toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ");
        if (CATEGORIES.contains(normalized)) {
            return normalized;
        }
        return flagged ? "other" : "none";
    }

    private static String reason(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return "";
        }
        String collapsed = node.asText().replaceAll("\\s+", " ").trim();
        if (collapsed.codePointCount(0, collapsed.length()) <= MAX_REASON) {
            return collapsed;
        }
        int end = collapsed.offsetByCodePoints(0, MAX_REASON);
        return collapsed.substring(0, end);
    }
}
