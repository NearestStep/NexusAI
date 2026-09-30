package io.github.neareststep.nexusai.config;

import java.util.Locale;
import java.util.Objects;

/**
 * One last model tried after every {@code model-queue} entry is unavailable, failed, or rejected.
 * Both fields are required. A blank provider or model means "not configured".
 */
public record FallbackModel(String provider, String model) {

    public FallbackModel {
        provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        model = model == null ? "" : model.trim();
    }

    public static FallbackModel none() {
        return new FallbackModel("", "");
    }

    public static FallbackModel of(String provider, String model) {
        FallbackModel parsed = new FallbackModel(provider, model);
        return parsed.configured() ? parsed : none();
    }

    public boolean configured() {
        return !provider.isEmpty() && !model.isEmpty();
    }

    public String storageId() {
        return provider + "|" + model;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FallbackModel that)) {
            return false;
        }
        return provider.equals(that.provider) && model.equals(that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model);
    }
}
