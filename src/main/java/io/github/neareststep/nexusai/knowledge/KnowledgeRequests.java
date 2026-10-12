package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;
import io.github.neareststep.nexusai.prompt.NamedPrompt;

import java.util.List;

/**
 * Resolves the prompt override ({@code knowledge-select}, {@code knowledge-keywords})
 * against {@code knowledge.select}.
 */
public final class KnowledgeRequests {

    private KnowledgeRequests() {
    }

    public static KnowledgeSelect effective(KnowledgeSelect promptMode, KnowledgeSelect global) {
        if (promptMode != null) {
            return promptMode;
        }
        return global == null ? KnowledgeSelect.FULL : global;
    }

    public static KnowledgeRequest of(NamedPrompt prompt, KnowledgeSelect global, String text) {
        KnowledgeSelect mode = global == null ? KnowledgeSelect.FULL : global;
        List<String> keywords = List.of();
        if (prompt != null) {
            mode = effective(prompt.knowledgeSelect(), global);
            keywords = prompt.knowledgeKeywords();
        }
        return new KnowledgeRequest(mode, text, keywords);
    }
}
