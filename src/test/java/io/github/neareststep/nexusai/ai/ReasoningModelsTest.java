package io.github.neareststep.nexusai.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningModelsTest {

    @Test
    void chatModelsAreNotReasoningModels() {
        assertFalse(ReasoningModels.isReasoning("gpt-4o-mini"));
        assertFalse(ReasoningModels.isReasoning("gpt-4o"));
        assertFalse(ReasoningModels.isReasoning("llama-3.1-8b"));
        assertFalse(ReasoningModels.usesCompletionTokenCap("gpt-4o-mini"));
    }

    @Test
    void knownReasoningFamiliesAreDetected() {
        assertTrue(ReasoningModels.isReasoning("o1"));
        assertTrue(ReasoningModels.isReasoning("o1-mini"));
        assertTrue(ReasoningModels.isReasoning("o3-mini"));
        assertTrue(ReasoningModels.isReasoning("openai/o4-mini"));
        assertTrue(ReasoningModels.isReasoning("openai/gpt-oss-20b"));
        assertTrue(ReasoningModels.isReasoning("deepseek-r1"));
        assertTrue(ReasoningModels.isReasoning("deepseek-reasoner"));
        assertTrue(ReasoningModels.isReasoning("qwq-32b"));

        assertTrue(ReasoningModels.usesCompletionTokenCap("o3"));
        assertTrue(ReasoningModels.usesCompletionTokenCap("o4-mini"));
        assertFalse(ReasoningModels.usesCompletionTokenCap("openai/gpt-oss-20b"));
        assertFalse(ReasoningModels.usesCompletionTokenCap("deepseek-reasoner"));
    }
}
