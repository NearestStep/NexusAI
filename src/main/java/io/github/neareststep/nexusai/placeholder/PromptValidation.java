package io.github.neareststep.nexusai.placeholder;

/**
 * Shared prompt validation for PlaceholderAPI params (package-visible for tests).
 */
final class PromptValidation {

    private PromptValidation() {
    }

    static boolean isUsablePrompt(String prompt, int maxLength) {
        return prompt != null && !prompt.isEmpty() && prompt.length() <= maxLength;
    }
}
