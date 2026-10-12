package io.github.neareststep.nexusai.api;

/**
 * How a prompt picks text from {@code knowledge/} files.
 * <p>
 * New values may appear in a later version. A {@code switch} on this enum should keep a
 * {@code default} branch.
 */
public enum KnowledgeSelect {
    /** Whole files, the same block as 1.1. */
    FULL,
    /** Paragraphs whose words match the request. There is no vector search and no embeddings. */
    KEYWORDS
}
