package io.github.neareststep.nexusai.placeholder;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VarSubstitutorTest {

    @Test
    void applyReplacesBraceTokensWithLiteralVars() {
        Map<String, String> vars = Map.of("player_name", "Steve");
        assertEquals("Hello, Steve!", VarSubstitutor.apply("Hello, {player_name}!", vars, null));
    }

    @Test
    void applyReplacesMultipleTokens() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("player_name", "Steve");
        vars.put("world", "world");
        assertEquals(
                "A Steve in world",
                VarSubstitutor.apply("A {player_name} in {world}", vars, null)
        );
    }

    @Test
    void percentTemplatesWithoutPlayerBecomeEmpty() {
        Map<String, String> vars = Map.of("player_name", "%player_name%");
        assertEquals("Hello, !", VarSubstitutor.apply("Hello, {player_name}!", vars, null));
    }

    @Test
    void appendVarsRulesListsTokens() {
        String prompt = "Short welcome";
        String withRules = VarSubstitutor.appendVarsRules(prompt, Map.of("player_name", "%player_name%"));
        assertTrue(withRules.startsWith(prompt));
        assertTrue(withRules.contains("{player_name}"));
        assertTrue(withRules.contains("Rules:"));
        assertFalse(withRules.contains("%player_name%"));
    }

    @Test
    void appendVarsRulesNoopWhenEmpty() {
        assertEquals("tip", VarSubstitutor.appendVarsRules("tip", Map.of()));
    }
}
