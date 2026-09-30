package io.github.neareststep.nexusai.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Opt-in chat moderation. Disabled unless {@code moderation.enabled} is true.
 * A blank provider or model means the check uses the model queue.
 */
public record ModerationSettings(
        boolean enabled,
        String provider,
        String model,
        int maxChecksPerMinute,
        int playerCooldownSeconds,
        int minLength,
        String systemPrompt,
        double temperature,
        int maxTokens
) {

    public static final String DEFAULT_SYSTEM_PROMPT = """
            Classify the Minecraft chat message in the user turn.
            Reply with one JSON object and no other text:
            {"flagged":false,"category":"none","reason":""}
            Set flagged to true only for toxicity, insult, veiled insult, harassment, or spam.
            category must be one of: toxicity, insult, veiled insult, harassment, spam, none.
            reason is one short sentence when flagged, and empty otherwise.""";

    public static ModerationSettings defaults() {
        return new ModerationSettings(false, "", "", 30, 15, 8, DEFAULT_SYSTEM_PROMPT, 0.0d, 80);
    }

    public static ModerationSettings load(FileConfiguration config) {
        ModerationSettings defaults = defaults();
        if (config == null) {
            return defaults;
        }
        String provider = config.getString("moderation.provider", "");
        String model = config.getString("moderation.model", "");
        provider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        model = model == null ? "" : model.trim();
        String system = config.getString("moderation.system-prompt", "");
        if (system == null || system.isBlank()) {
            system = DEFAULT_SYSTEM_PROMPT;
        }
        return new ModerationSettings(
                config.getBoolean("moderation.enabled", false),
                provider,
                model,
                Math.max(1, config.getInt("moderation.max-checks-per-minute", defaults.maxChecksPerMinute())),
                Math.max(0, config.getInt("moderation.player-cooldown-seconds", defaults.playerCooldownSeconds())),
                Math.max(0, config.getInt("moderation.min-length", defaults.minLength())),
                system.strip(),
                config.getDouble("moderation.temperature", defaults.temperature()),
                Math.max(0, config.getInt("moderation.max-tokens", defaults.maxTokens()))
        );
    }

    /**
     * Both {@code provider} and {@code model} are required to pin an endpoint.
     * Either one alone still uses the model queue.
     */
    public boolean pinned() {
        return !provider.isEmpty() && !model.isEmpty();
    }
}
