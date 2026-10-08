package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.api.KnowledgeSelect;

import java.util.List;

/**
 * The {@code /nai test} selection line: {@code rules#3, faq#1 (keywords)} or {@code rules, faq (full)}.
 */
public final class KnowledgeSummaries {

    private KnowledgeSummaries() {
    }

    public static String format(List<String> labels, KnowledgeSelect mode) {
        String listed = labels == null || labels.isEmpty() ? "none" : String.join(", ", labels);
        String name = mode == KnowledgeSelect.KEYWORDS ? "keywords" : "full";
        return listed + " (" + name + ")";
    }
}
