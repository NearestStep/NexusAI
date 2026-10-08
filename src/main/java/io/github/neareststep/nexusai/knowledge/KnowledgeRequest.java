package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;

import java.util.List;

/**
 * What to inject for one call. {@link KnowledgeSelect#FULL} ignores the query text.
 */
public record KnowledgeRequest(KnowledgeSelect mode, String text, List<String> keywords) {

    public KnowledgeRequest {
        mode = mode == null ? KnowledgeSelect.FULL : mode;
        text = text == null ? "" : text;
        keywords = keywords == null || keywords.isEmpty() ? List.of() : List.copyOf(keywords);
    }

    public static KnowledgeRequest full() {
        return new KnowledgeRequest(KnowledgeSelect.FULL, "", List.of());
    }
}
