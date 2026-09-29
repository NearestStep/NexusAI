package io.github.neareststep.nexusai.config;

/**
 * One configured {@code model-queue} row. {@code dailyRequestLimit} of {@code 0} means no daily cap.
 */
public record QueueEntryConfig(String provider, String model, int dailyRequestLimit) {

    public QueueEntryConfig {
        provider = provider == null ? "" : provider;
        model = model == null ? "" : model;
        dailyRequestLimit = Math.max(0, dailyRequestLimit);
    }
}
