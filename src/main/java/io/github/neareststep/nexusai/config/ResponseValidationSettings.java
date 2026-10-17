package io.github.neareststep.nexusai.config;

import io.github.neareststep.nexusai.api.InvalidJsonAction;
import io.github.neareststep.nexusai.api.ResponseValidation;

/**
 * {@code plugin-api.response-validation} from {@code config.yml}.
 */
public final class ResponseValidationSettings {

    private final boolean enabled;
    private final InvalidJsonAction onInvalid;
    private final int maxRetries;
    private final boolean deliverOnRegion;

    private ResponseValidationSettings(
            boolean enabled,
            InvalidJsonAction onInvalid,
            int maxRetries,
            boolean deliverOnRegion
    ) {
        this.enabled = enabled;
        this.onInvalid = onInvalid == null ? InvalidJsonAction.RETRY : onInvalid;
        this.maxRetries = maxRetries;
        this.deliverOnRegion = deliverOnRegion;
    }

    public static ResponseValidationSettings defaults() {
        return new ResponseValidationSettings(true, InvalidJsonAction.RETRY, 1, false);
    }

    public static ResponseValidationSettings of(
            boolean enabled,
            InvalidJsonAction onInvalid,
            int maxRetries,
            boolean deliverOnRegion
    ) {
        int capped = Math.max(0, Math.min(ResponseValidation.MAX_RETRIES, maxRetries));
        return new ResponseValidationSettings(enabled, onInvalid, capped, deliverOnRegion);
    }

    public boolean enabled() {
        return enabled;
    }

    public InvalidJsonAction onInvalid() {
        return onInvalid;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public boolean deliverOnRegion() {
        return deliverOnRegion;
    }
}
