package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.dto.ChatCompletionRequest;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.StructuredMode;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * JSON request body, provider mode, and the in-memory downgrade from {@code auto}.
 * The downgrade is kept until {@link #clear()}, which {@code /nai reload} calls.
 */
@ApiStatus.Internal
public final class StructuredOutputSupport {

    public static final int DEFAULT_MAX_TOKENS = 1024;

    static final String INSTRUCTION_PREFIX =
            "Reply with one JSON object only, no markdown, matching this JSON Schema: ";

    private static final ConcurrentHashMap<String, StructuredMode> MEMORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    private StructuredOutputSupport() {
    }

    /** Drops remembered downgrades. The next {@code auto} call tries {@code json_schema} again. */
    public static void clear() {
        MEMORY.clear();
        LOGGED.clear();
    }

    public static GenerationOverrides call(GenerationOverrides overrides, JsonSchema schema) {
        GenerationOverrides base = overrides == null ? GenerationOverrides.none() : overrides;
        return base.withStructured(new Call(schema, List.of(), new AtomicInteger()));
    }

    public static boolean isJson(GenerationOverrides overrides) {
        return callOf(overrides) != null;
    }

    public static String instruction(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        if (call == null) {
            return "";
        }
        return INSTRUCTION_PREFIX + call.schema.json();
    }

    /**
     * Picks the mode for this provider and model and stores it on the call.
     * {@code configured} is {@code auto}, {@code json-schema}, {@code json-object}, or {@code prompt}.
     */
    public static GenerationOverrides prepare(
            GenerationOverrides overrides,
            String providerId,
            String model,
            String configured
    ) {
        Call call = callOf(overrides);
        if (call == null) {
            return overrides;
        }
        call.configured = configured == null || configured.isBlank() ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        call.active = resolve(providerId, model, call.configured);
        return overrides;
    }

    public static StructuredMode active(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        if (call == null || call.active == null) {
            return StructuredMode.JSON_SCHEMA;
        }
        return call.active;
    }

    /** Remembers the queue row that sent this call so a repair uses that same row. */
    public static void noteRow(GenerationOverrides overrides, int queueIndex, boolean dedicated) {
        Call call = callOf(overrides);
        if (call == null) {
            return;
        }
        call.rowIndex = queueIndex;
        call.dedicated = dedicated;
    }

    public static int rowIndex(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        return call == null ? -1 : call.rowIndex;
    }

    public static boolean dedicatedRow(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        return call != null && call.dedicated;
    }

    public static void noteAttempt(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        if (call != null) {
            call.attempts.incrementAndGet();
        }
    }

    public static int attempts(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        return call == null ? 0 : call.attempts.get();
    }

    public static Object responseFormat(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        if (call == null || call.active == null || call.active == StructuredMode.PROMPT_ONLY) {
            return null;
        }
        if (call.active == StructuredMode.JSON_OBJECT) {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("type", "json_object");
            return object;
        }
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("name", "nexusai_" + call.schema.hash());
        inner.put("schema", schemaNode(call.schema));
        inner.put("strict", call.schema.strict());
        format.put("json_schema", inner);
        return format;
    }

    public static List<ChatCompletionRequest.Message> extraMessages(GenerationOverrides overrides) {
        Call call = callOf(overrides);
        if (call == null || call.extra.isEmpty()) {
            return List.of();
        }
        List<ChatCompletionRequest.Message> messages = new ArrayList<>();
        for (Turn turn : call.extra) {
            messages.add(new ChatCompletionRequest.Message(turn.role, turn.content));
        }
        return messages;
    }

    /**
     * One step down when {@code auto} was refused. Does not cool the row and is not a repair.
     * Returns true when the same call should be sent again in the lower mode.
     */
    public static boolean downgrade(
            GenerationOverrides overrides,
            String providerId,
            String model,
            AiRequestException error,
            Logger logger
    ) {
        Call call = callOf(overrides);
        if (call == null || error == null || call.active == null) {
            return false;
        }
        if (!allowsDowngrade(call.configured, call.active)) {
            return false;
        }
        int status = error.status();
        if (status != 400 && status != 422) {
            return false;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("response_format")
                && !message.contains("json_schema")
                && !message.contains("json_object")) {
            return false;
        }
        StructuredMode next = switch (call.active) {
            case JSON_SCHEMA -> StructuredMode.JSON_OBJECT;
            case JSON_OBJECT -> StructuredMode.PROMPT_ONLY;
            case PROMPT_ONLY -> null;
        };
        if (next == null) {
            return false;
        }
        StructuredMode from = call.active;
        call.active = next;
        MEMORY.put(key(providerId, model), next);
        String line = display(providerId, model) + ": " + wire(from) + " not supported, using " + wire(next);
        if (LOGGED.putIfAbsent(line, Boolean.TRUE) == null && logger != null) {
            logger.info(line);
        }
        return true;
    }

    /**
     * A new call for the one repair. {@code length} omits the model reply and the caller
     * sets the doubled {@code max_tokens}.
     */
    public static GenerationOverrides repair(
            GenerationOverrides overrides,
            String assistant,
            List<String> errors,
            boolean length
    ) {
        Call call = callOf(overrides);
        if (call == null) {
            return overrides;
        }
        List<Turn> extra = List.of();
        if (!length) {
            extra = List.of(
                    new Turn("assistant", JsonRepair.assistantMessage(assistant)),
                    new Turn("user", JsonRepair.userMessage(errors)));
        }
        Call next = new Call(call.schema, extra, new AtomicInteger());
        next.active = call.active;
        next.configured = call.configured;
        next.rowIndex = call.rowIndex;
        next.dedicated = call.dedicated;
        return overrides.withStructured(next);
    }

    public static StructuredMode remembered(String providerId, String model) {
        return MEMORY.get(key(providerId, model));
    }

    private static StructuredMode resolve(String providerId, String model, String configured) {
        String mode = configured == null ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        StructuredMode fixed = switch (mode) {
            case "json-schema" -> StructuredMode.JSON_SCHEMA;
            case "json-object" -> StructuredMode.JSON_OBJECT;
            case "prompt" -> StructuredMode.PROMPT_ONLY;
            default -> null;
        };
        if (fixed != null) {
            return fixed;
        }
        StructuredMode remembered = MEMORY.get(key(providerId, model));
        return remembered == null ? StructuredMode.JSON_SCHEMA : remembered;
    }

    private static JsonNode schemaNode(JsonSchema schema) {
        try {
            return JsonMappers.MAPPER.readTree(schema.json());
        } catch (Exception ex) {
            return JsonMappers.MAPPER.createObjectNode();
        }
    }

    private static String key(String providerId, String model) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        String name = model == null ? "" : model.trim();
        return provider + "\0" + name;
    }

    private static String display(String providerId, String model) {
        String provider = providerId == null ? "" : providerId.trim();
        String name = model == null ? "" : model.trim();
        return provider + "/" + name;
    }

    private static String wire(StructuredMode mode) {
        return switch (mode) {
            case JSON_SCHEMA -> "json_schema";
            case JSON_OBJECT -> "json_object";
            case PROMPT_ONLY -> "prompt";
        };
    }

    private static Call callOf(GenerationOverrides overrides) {
        if (overrides == null) {
            return null;
        }
        Object structured = overrides.structured();
        return structured instanceof Call call ? call : null;
    }

    /** Fixed provider modes stay on that mode. {@code auto} may step down until {@code prompt}. */
    static boolean allowsDowngrade(String configured, StructuredMode active) {
        String mode = configured == null || configured.isBlank() ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        if (!"auto".equals(mode)) {
            return false;
        }
        return active != null && active != StructuredMode.PROMPT_ONLY;
    }

    private record Turn(String role, String content) {
    }

    private static final class Call {
        private final JsonSchema schema;
        private final List<Turn> extra;
        private final AtomicInteger attempts;
        private volatile StructuredMode active = StructuredMode.JSON_SCHEMA;
        private volatile String configured = "auto";
        private volatile int rowIndex = -1;
        private volatile boolean dedicated;

        private Call(JsonSchema schema, List<Turn> extra, AtomicInteger attempts) {
            this.schema = schema;
            this.extra = extra == null ? List.of() : List.copyOf(extra);
            this.attempts = attempts == null ? new AtomicInteger() : attempts;
        }
    }
}
