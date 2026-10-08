package io.github.neareststep.nexusai.json;

import io.github.neareststep.nexusai.api.JsonSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A provider call is not part of the default build. Set {@code NEXUSAI_LIVE_JSON=true} only when a
 * local provider is already configured outside this repository.
 */
@Tag("live")
class JsonLiveTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "NEXUSAI_LIVE_JSON", matches = "true")
    void questSchemaStaysInsideTheSupportedSubset() {
        JsonSchema schema = JsonSchema.parse("""
                {"type":"object","additionalProperties":false,
                 "required":["title","goal","reward"],
                 "properties":{
                   "title":{"type":"string","maxLength":40},
                   "goal":{"type":"string","maxLength":200},
                   "reward":{"type":"integer","minimum":1,"maximum":1000}}}
                """);
        assertEquals(16, schema.hash().length());
        assertEquals(true, schema.strict());
    }
}
