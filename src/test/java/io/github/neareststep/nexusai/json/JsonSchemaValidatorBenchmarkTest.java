package io.github.neareststep.nexusai.json;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.neareststep.nexusai.api.JsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Micro-benchmark for local schema validation. Prints before/after-style timings for the PR.
 * Not a TPS load test; that stays in {@code docs/LOADTEST.md} Mode A scenarios.
 */
class JsonSchemaValidatorBenchmarkTest {

    private static final JsonSchema SCHEMA = JsonSchema.parse("""
            {"type":"object","additionalProperties":false,
             "required":["title","goal","reward","action"],
             "properties":{
               "title":{"type":"string","minLength":1,"maxLength":40},
               "goal":{"type":"string","minLength":1,"maxLength":200},
               "reward":{"type":"integer","minimum":1,"maximum":1000},
               "action":{"type":"string","enum":["give_item","open_shop","noop"]}}}
            """);

    private static final String VALID = """
            {"title":"Iron nails","goal":"Bring nails to the smith","reward":12,"action":"give_item"}
            """;
    private static final String INVALID = """
            {"title":"Iron nails","goal":"Bring nails to the smith","reward":"12","action":"explode"}
            """;

    @Test
    void validationCostStaysUnderOneMillisecondPerCall() throws Exception {
        JsonNode valid = JsonMappers.MAPPER.readTree(VALID);
        JsonNode invalid = JsonMappers.MAPPER.readTree(INVALID);
        int warmup = 2_000;
        int iterations = 20_000;
        for (int i = 0; i < warmup; i++) {
            JsonSchemaValidator.validate(SCHEMA, valid);
            JsonSchemaValidator.validate(SCHEMA, invalid);
            JsonDocuments.actionAllowlistErrors(valid, List.of("give_item", "open_shop"), "action");
        }
        long validNanos = 0L;
        long invalidNanos = 0L;
        long allowlistNanos = 0L;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            List<String> ok = JsonSchemaValidator.validate(SCHEMA, valid);
            validNanos += System.nanoTime() - start;
            assertTrue(ok.isEmpty());

            start = System.nanoTime();
            List<String> bad = JsonSchemaValidator.validate(SCHEMA, invalid);
            invalidNanos += System.nanoTime() - start;
            assertTrue(!bad.isEmpty());

            start = System.nanoTime();
            List<String> allow = JsonDocuments.actionAllowlistErrors(
                    valid, List.of("give_item", "open_shop", "noop"), "action");
            allowlistNanos += System.nanoTime() - start;
            assertTrue(allow.isEmpty());
        }
        double validUs = validNanos / (iterations * 1_000.0d);
        double invalidUs = invalidNanos / (iterations * 1_000.0d);
        double allowUs = allowlistNanos / (iterations * 1_000.0d);
        System.out.printf(
                Locale.ROOT,
                "NAI-99 JsonSchemaValidator benchmark: valid=%.3fµs invalid=%.3fµs allowlist=%.3fµs (n=%d)%n",
                validUs,
                invalidUs,
                allowUs,
                iterations);
        assertTrue(validUs < 1_000.0d, "valid path too slow: " + validUs + "µs");
        assertTrue(invalidUs < 1_000.0d, "invalid path too slow: " + invalidUs + "µs");
        assertTrue(allowUs < 1_000.0d, "allowlist path too slow: " + allowUs + "µs");
    }
}
