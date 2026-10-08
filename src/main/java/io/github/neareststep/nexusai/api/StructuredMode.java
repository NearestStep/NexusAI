package io.github.neareststep.nexusai.api;

/**
 * How a JSON request asked the provider for an object.
 * <p>
 * New values may be added later. A {@code switch} should keep a {@code default} branch.
 */
public enum StructuredMode {
    /** {@code response_format} is {@code json_schema}. */
    JSON_SCHEMA,
    /** {@code response_format} is {@code json_object}. */
    JSON_OBJECT,
    /** The schema is only an instruction in the system message. */
    PROMPT_ONLY
}
