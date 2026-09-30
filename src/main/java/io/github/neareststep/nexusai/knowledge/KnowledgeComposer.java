package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.util.List;

/**
 * Puts knowledge into the system prompt after the admin text.
 * {@code OpenAiProvider} then appends the format instruction and the player-input guard,
 * so the guard stays last.
 */
public final class KnowledgeComposer {

    private KnowledgeComposer() {
    }

    public static GenerationOverrides apply(GenerationOverrides overrides, String globalSystem, String block) {
        GenerationOverrides base = overrides == null ? GenerationOverrides.none() : overrides;
        if (block == null || block.isBlank()) {
            return base;
        }
        String admin = base.systemPrompt(globalSystem);
        String merged = admin == null || admin.isBlank()
                ? block
                : admin.stripTrailing() + "\n\n" + block;
        return base.withSystemPrompt(merged);
    }

    public static Prepared prepare(
            GenerationOverrides overrides,
            String globalSystem,
            KnowledgeBase knowledge,
            List<String> names
    ) {
        KnowledgeBase source = knowledge == null ? KnowledgeBase.empty() : knowledge;
        String block = source.block(names);
        return new Prepared(apply(overrides, globalSystem, block), source.cacheToken(names));
    }

    public record Prepared(GenerationOverrides overrides, String cacheToken) {
    }
}
