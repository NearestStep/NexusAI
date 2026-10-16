package io.github.neareststep.nexusai.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-request overrides for {@code plugin-api.response-validation} on {@link NexusAIApi#generateJson}.
 * <p>
 * Null fields keep the value from {@code config.yml}. An empty {@link #allowedActions()} list does not
 * add an allowlist check; use {@link JsonSchema#withActionAllowlist} or {@link Builder#allowedActions}
 * to restrict action names.
 */
public final class ResponseValidation {

    /** Highest {@code max-retries} accepted on a request or in config. */
    public static final int MAX_RETRIES = 3;

    /** Longest allowlist NexusAI keeps on one request. */
    public static final int MAX_ALLOWED_ACTIONS = 64;

    /** Longest single action name. */
    public static final int MAX_ACTION_CHARS = 64;

    static final Pattern ACTION_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    static final Pattern ACTION_PROPERTY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final Boolean enabled;
    private final InvalidJsonAction onInvalid;
    private final Integer maxRetries;
    private final Boolean deliverOnRegion;
    private final List<String> allowedActions;
    private final String actionProperty;

    private ResponseValidation(
            Boolean enabled,
            InvalidJsonAction onInvalid,
            Integer maxRetries,
            Boolean deliverOnRegion,
            List<String> allowedActions,
            String actionProperty
    ) {
        this.enabled = enabled;
        this.onInvalid = onInvalid;
        this.maxRetries = maxRetries;
        this.deliverOnRegion = deliverOnRegion;
        this.allowedActions = allowedActions;
        this.actionProperty = actionProperty;
    }

    /** No overrides and no action allowlist. Config values apply as written. */
    public static ResponseValidation defaults() {
        return new ResponseValidation(null, null, null, null, List.of(), "action");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Empty when this request leaves {@code plugin-api.response-validation.enabled} alone. */
    public Optional<Boolean> enabled() {
        return Optional.ofNullable(enabled);
    }

    /** Empty when this request leaves {@code on-invalid} alone. */
    public Optional<InvalidJsonAction> onInvalid() {
        return Optional.ofNullable(onInvalid);
    }

    /** Empty when this request leaves {@code max-retries} alone. */
    public OptionalInt maxRetries() {
        return maxRetries == null ? OptionalInt.empty() : OptionalInt.of(maxRetries);
    }

    /** Empty when this request leaves {@code deliver-on-region} alone. */
    public Optional<Boolean> deliverOnRegion() {
        return Optional.ofNullable(deliverOnRegion);
    }

    /**
     * Extra allowlist checked on {@link #actionProperty()} after the schema check.
     * Empty means no extra allowlist beyond the schema.
     */
    public List<String> allowedActions() {
        return allowedActions;
    }

    /** JSON property checked against {@link #allowedActions()}. Default {@code action}. */
    public String actionProperty() {
        return actionProperty;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResponseValidation that)) {
            return false;
        }
        return Objects.equals(enabled, that.enabled)
                && onInvalid == that.onInvalid
                && Objects.equals(maxRetries, that.maxRetries)
                && Objects.equals(deliverOnRegion, that.deliverOnRegion)
                && allowedActions.equals(that.allowedActions)
                && actionProperty.equals(that.actionProperty);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, onInvalid, maxRetries, deliverOnRegion, allowedActions, actionProperty);
    }

    @Override
    public String toString() {
        return "ResponseValidation{enabled=" + enabled
                + ", onInvalid=" + onInvalid
                + ", maxRetries=" + maxRetries
                + ", deliverOnRegion=" + deliverOnRegion
                + ", allowedActions=" + allowedActions.size()
                + ", actionProperty=" + actionProperty
                + "}";
    }

    public static final class Builder {

        private Boolean enabled;
        private InvalidJsonAction onInvalid;
        private Integer maxRetries;
        private Boolean deliverOnRegion;
        private final LinkedHashSet<String> allowedActions = new LinkedHashSet<>();
        private String actionProperty = "action";

        private Builder() {
        }

        /** Overrides {@code plugin-api.response-validation.enabled}. */
        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        /** Overrides {@code plugin-api.response-validation.on-invalid}. */
        public Builder onInvalid(InvalidJsonAction action) {
            if (action == null) {
                throw new IllegalArgumentException("onInvalid is required");
            }
            this.onInvalid = action;
            return this;
        }

        /** Overrides {@code plugin-api.response-validation.max-retries}. Range 0..{@link #MAX_RETRIES}. */
        public Builder maxRetries(int retries) {
            if (retries < 0 || retries > MAX_RETRIES) {
                throw new IllegalArgumentException("maxRetries must be from 0 to " + MAX_RETRIES);
            }
            this.maxRetries = retries;
            return this;
        }

        /**
         * Overrides {@code plugin-api.response-validation.deliver-on-region}.
         * That config key defaults to false; pass {@code true} to opt in for this request.
         */
        public Builder deliverOnRegion(boolean value) {
            this.deliverOnRegion = value;
            return this;
        }

        /**
         * Replaces the allowlist. Names match {@code [A-Za-z0-9_.:-]{1,64}}. At most
         * {@link #MAX_ALLOWED_ACTIONS} names. Duplicates keep the first spelling.
         */
        public Builder allowedActions(Collection<String> actions) {
            allowedActions.clear();
            if (actions == null) {
                throw new IllegalArgumentException("allowedActions are required");
            }
            for (String action : actions) {
                addAction(action);
            }
            return this;
        }

        /** Same as {@link #allowedActions(Collection)}. */
        public Builder allowedActions(String... actions) {
            if (actions == null) {
                throw new IllegalArgumentException("allowedActions are required");
            }
            return allowedActions(List.of(actions));
        }

        /** JSON property for {@link #allowedActions}. Default {@code action}. */
        public Builder actionProperty(String property) {
            if (property == null || !ACTION_PROPERTY.matcher(property).matches()) {
                throw new IllegalArgumentException("actionProperty must match [A-Za-z_][A-Za-z0-9_]{0,63}");
            }
            this.actionProperty = property;
            return this;
        }

        public ResponseValidation build() {
            List<String> copy = allowedActions.isEmpty()
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(allowedActions));
            return new ResponseValidation(enabled, onInvalid, maxRetries, deliverOnRegion, copy, actionProperty);
        }

        private void addAction(String action) {
            if (action == null || !ACTION_NAME.matcher(action).matches()) {
                throw new IllegalArgumentException("action name must match [A-Za-z0-9_.:-]{1,64}");
            }
            if (allowedActions.size() >= MAX_ALLOWED_ACTIONS && !allowedActions.contains(action)) {
                throw new IllegalArgumentException("at most " + MAX_ALLOWED_ACTIONS + " allowed actions");
            }
            allowedActions.add(action);
        }
    }

    /** Parses {@code retry} / {@code fallback}. Unknown values return empty. */
    public static Optional<InvalidJsonAction> parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("retry".equals(normalized)) {
            return Optional.of(InvalidJsonAction.RETRY);
        }
        if ("fallback".equals(normalized)) {
            return Optional.of(InvalidJsonAction.FALLBACK);
        }
        return Optional.empty();
    }

    /** Stable allowlist token for cache keys. Empty when there is no allowlist. */
    public String allowlistToken() {
        if (allowedActions.isEmpty()) {
            return "";
        }
        Set<String> sorted = new LinkedHashSet<>();
        List<String> names = new ArrayList<>(allowedActions);
        Collections.sort(names);
        sorted.addAll(names);
        return actionProperty + "=" + String.join(",", sorted);
    }
}
