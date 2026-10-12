package io.github.neareststep.nexusai.api;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One finished {@link NexusAIApi#generateJson} call.
 * <p>
 * {@link #success()} is true only after the reply was extracted and checked against the schema.
 * {@link #json()} is the compact JSON text with string values cleaned. {@link #asMap()} uses
 * {@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@link List}, {@link Map}, and null.
 * <p>
 * Values came from a model. Check them again before placing one in a command, a name, or a path.
 */
public final class JsonGenerationResult {

    private final GenerationResult meta;
    private final boolean success;
    private final String json;
    private final Map<String, Object> map;
    private final StructuredMode mode;
    private final boolean repaired;
    private final List<String> validationErrors;

    private JsonGenerationResult(
            GenerationResult meta,
            boolean success,
            String json,
            Map<String, Object> map,
            StructuredMode mode,
            boolean repaired,
            List<String> validationErrors
    ) {
        this.meta = meta;
        this.success = success && meta != null && meta.success();
        this.json = json == null ? "" : json;
        this.map = map == null ? Map.of() : map;
        this.mode = mode == null ? StructuredMode.JSON_SCHEMA : mode;
        this.repaired = repaired;
        this.validationErrors = validationErrors == null || this.success ? List.of() : List.copyOf(validationErrors);
    }

    /** Not part of the plugin contract. */
    @ApiStatus.Internal
    public static JsonGenerationResult of(
            GenerationResult meta,
            String json,
            Map<String, Object> map,
            StructuredMode mode,
            boolean repaired,
            List<String> validationErrors
    ) {
        boolean ok = meta != null && meta.success();
        return new JsonGenerationResult(meta, ok, json, map, mode, repaired, validationErrors);
    }

    /** Metadata from the same pipeline as {@link NexusAIApi#generate}. */
    public GenerationResult meta() {
        return meta;
    }

    /** True when JSON was extracted and matched the schema. */
    public boolean success() {
        return success;
    }

    /** Compact JSON text. Empty when {@link #success()} is false. */
    public Optional<String> json() {
        return success ? Optional.of(json) : Optional.empty();
    }

    /**
     * The object as a map. Numbers that are integers and fit in a long are {@link Long}.
     * Other numbers are {@link Double}. Empty when {@link #success()} is false.
     */
    public Optional<Map<String, Object>> asMap() {
        return success ? Optional.of(map) : Optional.empty();
    }

    /** The mode that was actually requested after any downgrade. */
    public StructuredMode mode() {
        return mode;
    }

    /** True when the one correction retry was sent and this result came from the model. */
    public boolean repaired() {
        return repaired;
    }

    /** The last validation lines, at most 10. Empty when {@link #success()} is true. */
    public List<String> validationErrors() {
        return validationErrors;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonGenerationResult that)) {
            return false;
        }
        return success == that.success
                && repaired == that.repaired
                && mode == that.mode
                && Objects.equals(meta, that.meta)
                && Objects.equals(json, that.json)
                && Objects.equals(map, that.map)
                && Objects.equals(validationErrors, that.validationErrors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(meta, success, json, map, mode, repaired, validationErrors);
    }

    @Override
    public String toString() {
        return "JsonGenerationResult[success=" + success
                + ", mode=" + mode
                + ", repaired=" + repaired
                + ", errors=" + validationErrors.size()
                + "]";
    }
}
