package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.InvalidJsonAction;
import io.github.neareststep.nexusai.api.ResponseValidation;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ResponseValidationSettings;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;

import java.util.List;

/**
 * Config plus per-request overrides for one {@code generateJson} call.
 */
final class EffectiveResponseValidation {

    private final boolean enabled;
    private final InvalidJsonAction onInvalid;
    private final int maxRetries;
    private final boolean deliverOnRegion;
    private final List<String> allowedActions;
    private final String actionProperty;
    private final String allowlistToken;

    private EffectiveResponseValidation(
            boolean enabled,
            InvalidJsonAction onInvalid,
            int maxRetries,
            boolean deliverOnRegion,
            List<String> allowedActions,
            String actionProperty,
            String allowlistToken
    ) {
        this.enabled = enabled;
        this.onInvalid = onInvalid;
        this.maxRetries = maxRetries;
        this.deliverOnRegion = deliverOnRegion;
        this.allowedActions = allowedActions;
        this.actionProperty = actionProperty;
        this.allowlistToken = allowlistToken;
    }

    static EffectiveResponseValidation resolve(PluginConfig config, GenerationRequest request) {
        ResponseValidationSettings settings = config == null
                ? ResponseValidationSettings.defaults()
                : config.responseValidation();
        ResponseValidation override = request == null
                ? ResponseValidation.defaults()
                : request.responseValidation();
        boolean enabled = override.enabled().orElse(settings.enabled());
        InvalidJsonAction onInvalid = override.onInvalid().orElse(settings.onInvalid());
        int maxRetries = override.maxRetries().orElse(settings.maxRetries());
        if (maxRetries < 0) {
            maxRetries = 0;
        }
        if (maxRetries > ResponseValidation.MAX_RETRIES) {
            maxRetries = ResponseValidation.MAX_RETRIES;
        }
        boolean deliverOnRegion = override.deliverOnRegion().orElse(settings.deliverOnRegion());
        List<String> actions = List.copyOf(override.allowedActions());
        String property = override.actionProperty();
        String token = override.allowlistToken();
        if (!token.isEmpty()) {
            token = "actions:" + KnowledgeBase.sha256(token);
        }
        return new EffectiveResponseValidation(
                enabled, onInvalid, maxRetries, deliverOnRegion, actions, property, token);
    }

    boolean enabled() {
        return enabled;
    }

    boolean canRepair() {
        return enabled && onInvalid == InvalidJsonAction.RETRY && maxRetries > 0;
    }

    int maxRetries() {
        return maxRetries;
    }

    boolean deliverOnRegion() {
        return deliverOnRegion;
    }

    List<String> allowedActions() {
        return allowedActions;
    }

    String actionProperty() {
        return actionProperty;
    }

    String allowlistToken() {
        return allowlistToken;
    }
}
