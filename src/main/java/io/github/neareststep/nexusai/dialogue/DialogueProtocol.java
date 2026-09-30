package io.github.neareststep.nexusai.dialogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.ReasoningModels;
import io.github.neareststep.nexusai.ai.dto.ContentTexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI {@code tools} request and response mapping.
 * Action names come only from {@code tool_calls} or a native {@code function_call}. Message text is never scanned.
 */
public final class DialogueProtocol {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DialogueProtocol() {
    }

    public static byte[] requestJson(
            String model,
            String system,
            List<MemoryLine> messages,
            List<CharacterAction> tools,
            Double temperature,
            Integer maxTokens,
            Integer maxCompletionTokens,
            String reasoningEffort
    ) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        ArrayNode array = root.putArray("messages");
        ObjectNode systemMessage = array.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", PlayerInput.appendGuard(system, guardNeeded(system, messages)));
        if (messages != null) {
            for (MemoryLine line : messages) {
                ObjectNode message = array.addObject();
                message.put("role", line.role());
                message.put("content", line.text() == null ? "" : line.text());
            }
        }
        if (temperature != null) {
            root.put("temperature", temperature);
        }
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (maxCompletionTokens != null) {
            root.put("max_completion_tokens", maxCompletionTokens);
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            root.put("reasoning_effort", reasoningEffort);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = root.putArray("tools");
            for (CharacterAction action : tools) {
                ObjectNode tool = toolArray.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", action.name());
                function.put("description", action.description()
                        + " The server runs a fixed command. Pass no arguments.");
                ObjectNode parameters = function.putObject("parameters");
                parameters.put("type", "object");
                parameters.putObject("properties");
                parameters.put("additionalProperties", false);
            }
            root.put("tool_choice", "auto");
        }
        return MAPPER.writeValueAsBytes(root);
    }

    public static ParsedCompletion parse(String body) throws Exception {
        JsonNode tree = MAPPER.readTree(body == null ? "" : body);
        JsonNode message = tree.path("choices").path(0).path("message");
        if (message.isMissingNode() || message.isNull()) {
            return new ParsedCompletion(null, List.of());
        }
        String content = ContentTexts.read(message.get("content"));
        return new ParsedCompletion(content, toolNames(message));
    }

    /**
     * Native function calls only. A reply whose text is an action name is not a call.
     */
    public static List<String> toolNames(JsonNode message) {
        if (message == null || message.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        JsonNode calls = message.get("tool_calls");
        if (calls != null && calls.isArray()) {
            for (JsonNode call : calls) {
                addName(names, call.path("function").path("name").asText(""));
            }
        }
        JsonNode legacy = message.get("function_call");
        if (legacy != null && legacy.isObject()) {
            addName(names, legacy.path("name").asText(""));
        }
        return List.copyOf(names);
    }

    public static boolean unsupportedTools(int status, String body) {
        if (status != 400 && status != 422) {
            return false;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("tool") || lower.contains("function");
    }

    public static TokenBudget tokens(String model, Integer requested, String configuredEffort) {
        if (!ReasoningModels.isReasoning(model)) {
            return new TokenBudget(requested, null, null);
        }
        int floor = requested == null ? ReasoningModels.TOKEN_FLOOR : Math.max(requested, ReasoningModels.TOKEN_FLOOR);
        String effort = configuredEffort == null || configuredEffort.isBlank() ? null : configuredEffort;
        if (ReasoningModels.usesCompletionTokenCap(model)) {
            return new TokenBudget(null, floor, effort);
        }
        return new TokenBudget(floor, null, effort);
    }

    private static boolean guardNeeded(String system, List<MemoryLine> messages) {
        if (PlayerInput.containsWrappedInput(system)) {
            return true;
        }
        if (messages == null) {
            return false;
        }
        for (MemoryLine line : messages) {
            if (line != null && PlayerInput.containsWrappedInput(line.text())) {
                return true;
            }
        }
        return false;
    }

    private static void addName(List<String> names, String raw) {
        if (raw == null) {
            return;
        }
        String name = raw.trim();
        if (name.isEmpty() || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            return;
        }
        names.add(name);
    }

    public record MemoryLine(String role, String text) {
    }

    public record ParsedCompletion(String content, List<String> toolNames) {
    }

    public record TokenBudget(Integer maxTokens, Integer maxCompletionTokens, String reasoningEffort) {
    }
}
