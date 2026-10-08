package io.github.neareststep.nexusai.knowledge;

import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.util.List;

/**
 * Puts knowledge into the system prompt after the admin text.
 * {@code OpenAiProvider} then appends the format instruction and, when the request
 * contains wrapped player input, the player-input guard, so the guard stays last.
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
        return prepare(overrides, globalSystem, knowledge, names, KnowledgeRequest.full());
    }

    /**
     * {@link KnowledgeRequest#full()} keeps the 1.1 block and cache token.
     * Keyword mode selects paragraphs from {@code request} and still hashes the block that was sent.
     */
    public static Prepared prepare(
            GenerationOverrides overrides,
            String globalSystem,
            KnowledgeBase knowledge,
            List<String> names,
            KnowledgeRequest request
    ) {
        KnowledgeBase source = knowledge == null ? KnowledgeBase.empty() : knowledge;
        KnowledgeBase.Piece piece = source.render(names, request);
        return new Prepared(apply(overrides, globalSystem, piece.block()), piece.cacheToken(), piece.summary());
    }

    public record Prepared(GenerationOverrides overrides, String cacheToken, String summary) {
        public Prepared {
            cacheToken = cacheToken == null ? "" : cacheToken;
            summary = summary == null ? "" : summary;
        }
    }
}
