package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The mapper used to read model JSON. Duplicate keys are rejected and trailing tokens fail the parse.
 */
final class JsonMappers {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private JsonMappers() {
    }
}
