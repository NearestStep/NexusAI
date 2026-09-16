package io.github.neareststep.nexusai.placeholder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptValidationTest {

    @Test
    void rejectsEmptyOrTooLong() {
        assertFalse(PromptValidation.isUsablePrompt("", 128));
        assertFalse(PromptValidation.isUsablePrompt(null, 128));
        assertFalse(PromptValidation.isUsablePrompt("a".repeat(129), 128));
        assertTrue(PromptValidation.isUsablePrompt("a".repeat(128), 128));
        assertTrue(PromptValidation.isUsablePrompt("hello", 128));
    }
}
