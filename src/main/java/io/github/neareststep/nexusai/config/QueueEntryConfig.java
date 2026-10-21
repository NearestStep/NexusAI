package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.text.Utf16;

/**
 * One configured {@code model-queue} row.
 * A limit of {@code 0} means that cap is not set.
 * {@code dailyTokenLimit} is enforced only while {@code quotas.enabled} is true.
 */
public record QueueEntryConfig(String provider, String model, int dailyRequestLimit, int dailyTokenLimit) {

    public QueueEntryConfig(String provider, String model, int dailyRequestLimit) {
        this(provider, model, dailyRequestLimit, 0);
    }

    public QueueEntryConfig {
        provider = provider == null ? "" : Utf16.replaceLoneSurrogates(provider);
        model = model == null ? "" : Utf16.replaceLoneSurrogates(model);
        dailyRequestLimit = Math.max(0, dailyRequestLimit);
        dailyTokenLimit = Math.max(0, dailyTokenLimit);
    }
}
