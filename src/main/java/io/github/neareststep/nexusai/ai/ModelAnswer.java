package io.github.neareststep.nexusai.ai;

import java.time.Duration;

/**
 * One completed reply. {@code cacheTtl} is null when the caller should use the prompt TTL
 * or the cache default. A length-truncated reply sets {@link LengthCutoff#CACHE_TTL}.
 */
public record ModelAnswer(String text, Duration cacheTtl) {

    public static ModelAnswer text(String text) {
        return new ModelAnswer(text, null);
    }
}
