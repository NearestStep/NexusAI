package io.github.neareststep.nexusai.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvSubstitutorTest {

    @Test
    void envPrefixMatchesTheBareName() {
        Map<String, String> env = Map.of("GROQ_API_KEY", "sk-from-env");
        assertEquals(
                EnvSubstitutor.apply("key=${GROQ_API_KEY}", env::get),
                EnvSubstitutor.apply("key=${ENV:GROQ_API_KEY}", env::get));
        assertEquals("sk-from-env", EnvSubstitutor.apply("${ENV:GROQ_API_KEY}", env::get));
        assertTrue(EnvSubstitutor.referencesEnv("${ENV:GROQ_API_KEY}"));
        assertTrue(EnvSubstitutor.referencesEnv("${GROQ_API_KEY}"));
        assertFalse(EnvSubstitutor.referencesEnv("sk-literal"));
    }

    @Test
    void aMissingPrefixedVariableIsRecordedByName() {
        List<String> missing = new ArrayList<>();
        assertEquals("", EnvSubstitutor.apply("${ENV:MISSING_GROQ}", name -> null, missing));
        assertEquals(List.of("MISSING_GROQ"), missing);
        assertEquals("", EnvSubstitutor.apply("${MISSING_GROQ}", name -> null, missing));
        assertEquals(List.of("MISSING_GROQ", "MISSING_GROQ"), missing);
    }
}
