package io.github.neareststep.nexusai.api;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * Package bridge for fields that must not grow the public {@link GenerationRequest} method list.
 * Not part of the plugin API.
 */
@ApiStatus.Internal
public final class GenerationRequestFacts {

    private GenerationRequestFacts() {
    }

    public static boolean systemPromptSpecified(GenerationRequest request) {
        return request != null && request.systemPromptSpecified();
    }

    public static String systemPromptRaw(GenerationRequest request) {
        return request == null ? "" : request.systemPromptRaw();
    }

    public static boolean temperatureSpecified(GenerationRequest request) {
        return request != null && request.temperatureSpecified();
    }

    public static double temperatureRaw(GenerationRequest request) {
        return request == null ? 0.0d : request.temperatureRaw();
    }

    public static boolean maxTokensSpecified(GenerationRequest request) {
        return request != null && request.maxTokensSpecified();
    }

    public static int maxTokensRaw(GenerationRequest request) {
        return request == null ? 0 : request.maxTokensRaw();
    }

    /** Entity from {@link GenerationRequest.Builder#player}, or null when only an id was set. */
    public static Player player(GenerationRequest request) {
        return request == null ? null : request.playerEntity();
    }
}
