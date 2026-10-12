package io.github.neareststep.nexusai.placeholder;

import io.github.neareststep.nexusai.prompt.ResolvedPrompt;

/**
 * Spec §4.3: a named prompt's admission key is the prompt id, shared with {@code generate}
 * for that prompt. A literal placeholder is not that prompt, so it keeps the rendered text.
 */
final class PlaceholderAdmission {

    private PlaceholderAdmission() {
    }

    static String key(ResolvedPrompt resolved, String promptText) {
        if (resolved != null && resolved.named() && resolved.id() != null && !resolved.id().isBlank()) {
            return resolved.id();
        }
        return promptText == null ? "" : promptText;
    }
}
