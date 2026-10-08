package io.github.neareststep.nexusai.budget;

/**
 * Tokens already spent plus tokens reserved for in-flight calls on one queue row.
 * {@link RowTokens#NONE} reports zero, which leaves a row selectable.
 */
@FunctionalInterface
public interface RowTokens {

    RowTokens NONE = storageId -> 0L;

    long used(String storageId);
}
