package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.ModerationSettings;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks one public chat line after it has already been delivered.
 * The helper never cancels chat, punishes a player, or runs a command.
 */
public final class ModerationService {

    static final String UNPARSEABLE =
            "Moderation verdict was not JSON and was treated as not flagged";

    private final ModerationSettings settings;
    private final PluginConfig config;
    private final ModelQueue queue;
    private final OpenAiProvider http;
    private final Executor executor;
    private final ModerationGate gate;
    private final ModerationLog log;
    private final StaffNotifier notifier;
    private final Logger logger;
    private final LongSupplier clock;

    public ModerationService(
            ModerationSettings settings,
            PluginConfig config,
            ModelQueue queue,
            OpenAiProvider http,
            Executor executor,
            ModerationLog log,
            StaffNotifier notifier,
            Logger logger
    ) {
        this(settings, config, queue, http, executor, log, notifier, logger, System::currentTimeMillis);
    }

    public ModerationService(
            ModerationSettings settings,
            PluginConfig config,
            ModelQueue queue,
            OpenAiProvider http,
            Executor executor,
            ModerationLog log,
            StaffNotifier notifier,
            Logger logger,
            LongSupplier clock
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.config = Objects.requireNonNull(config, "config");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.http = http;
        this.executor = executor;
        this.gate = new ModerationGate(settings.maxChecksPerMinute(), settings.playerCooldownSeconds());
        this.log = log;
        this.notifier = notifier == null ? (player, message, category, reason) -> { } : notifier;
        this.logger = logger == null ? Logger.getLogger("nexusai.moderation") : logger;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public int checksToday() {
        return queue.moderationChecks();
    }

    public int flagsToday() {
        return queue.moderationFlags();
    }

    /**
     * Queues the check and returns without waiting for the model.
     */
    public void submit(UUID playerId, String playerName, String message, boolean bypass) {
        if (!settings.enabled() || executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    check(playerId, playerName, message, bypass);
                } catch (Throwable thrown) {
                    logger.log(Level.WARNING, "Chat moderation check failed", thrown);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.fine("Skipped a chat moderation check because the executor is shut down");
        }
    }

    public Decision check(UUID playerId, String playerName, String message, boolean bypass) {
        if (!settings.enabled()) {
            return Decision.skipped(Skip.DISABLED);
        }
        if (bypass) {
            return Decision.skipped(Skip.BYPASS);
        }
        String text = message == null ? "" : message.trim();
        if (text.codePointCount(0, text.length()) < settings.minLength()) {
            return Decision.skipped(Skip.TOO_SHORT);
        }
        long now = clock.getAsLong();
        Skip reserved = gate.tryReserve(playerId, now);
        if (reserved != null) {
            return Decision.skipped(reserved);
        }
        Optional<ModelQueue.Choice> choice = reserveDaily(now);
        if (choice.isEmpty()) {
            gate.release(playerId, now);
            return Decision.skipped(dailyBlocked(now) ? Skip.DAILY_CAP : Skip.UNAVAILABLE);
        }
        queue.recordModerationCheck();
        String wrapped = PlayerInput.wrap(text);
        String reply;
        try {
            reply = call(wrapped, choice.get());
        } catch (AiRequestException e) {
            logger.log(Level.FINE, "Chat moderation request failed: " + e.getMessage());
            return new Decision(Skip.CHECKED, false, "none", "");
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Chat moderation request failed", e);
            return new Decision(Skip.CHECKED, false, "none", "");
        }
        Optional<ModerationVerdict> parsed = VerdictParser.parse(reply);
        if (parsed.isEmpty()) {
            logger.fine(UNPARSEABLE + ": " + SecretMask.redact(truncate(reply), config.configuredSecrets()));
            return new Decision(Skip.CHECKED, false, "none", "");
        }
        ModerationVerdict verdict = parsed.get();
        if (!verdict.flagged()) {
            return new Decision(Skip.CHECKED, false, verdict.category(), verdict.reason());
        }
        queue.recordModerationFlag();
        if (log != null) {
            log.append(playerId, playerName, text, verdict.category(), verdict.reason());
        }
        notifier.flagged(playerName == null ? "" : playerName, text, verdict.category(), verdict.reason());
        logger.info("Chat moderation flagged " + (playerName == null ? "a player" : playerName)
                + " (" + verdict.category() + ")"
                + (verdict.reason().isBlank() ? "" : ": " + verdict.reason()));
        return new Decision(Skip.CHECKED, true, verdict.category(), verdict.reason());
    }

    private Optional<ModelQueue.Choice> reserveDaily(long now) {
        if (!settings.pinned()) {
            Optional<ModelQueue.Choice> selected = queue.select(now);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            ProviderSettings provider = config.provider(selected.get().provider());
            if (!canCall(provider) || !queue.tryConsume(selected.get().index(), now)) {
                return Optional.empty();
            }
            return selected;
        }
        ProviderSettings provider = config.provider(settings.provider());
        if (!canCall(provider)) {
            return Optional.empty();
        }
        for (ModelQueue.Status row : queue.status(now)) {
            if (!row.provider().equals(settings.provider()) || !row.model().equals(settings.model())) {
                continue;
            }
            if (!queue.tryConsume(row.index(), now)) {
                return Optional.empty();
            }
            return Optional.of(new ModelQueue.Choice(row.index(), row.provider(), row.model()));
        }
        return Optional.of(new ModelQueue.Choice(-1, settings.provider(), settings.model()));
    }

    private boolean canCall(ProviderSettings provider) {
        if (provider == null || provider.url() == null || provider.url().isBlank()) {
            return false;
        }
        return provider.hasKeys() || config.providerAllowsKeyless(provider);
    }

    private boolean dailyBlocked(long now) {
        if (!settings.pinned()) {
            boolean sawRow = false;
            boolean underCap = false;
            for (ModelQueue.Status row : queue.status(now)) {
                sawRow = true;
                if (row.dailyLimit() <= 0 || row.requestsToday() < row.dailyLimit()) {
                    underCap = true;
                }
            }
            return sawRow && !underCap;
        }
        for (ModelQueue.Status row : queue.status(now)) {
            if (row.provider().equals(settings.provider())
                    && row.model().equals(settings.model())
                    && row.dailyLimit() > 0
                    && row.requestsToday() >= row.dailyLimit()) {
                return true;
            }
        }
        return false;
    }

    private String call(String wrapped, ModelQueue.Choice choice) {
        if (http == null) {
            throw new AiRequestException(
                    io.github.neareststep.nexusai.ai.AiErrorKind.OTHER, 0, "Moderation HTTP client is not ready", null);
        }
        String providerId = choice.provider();
        String model = choice.model();
        ProviderSettings provider = config.provider(providerId);
        if (provider == null || provider.url() == null || provider.url().isBlank()) {
            throw new AiRequestException(
                    io.github.neareststep.nexusai.ai.AiErrorKind.OTHER, 0, "Unknown moderation provider " + providerId, null);
        }
        GenerationOverrides overrides = GenerationOverrides.of(
                true,
                settings.systemPrompt(),
                true,
                settings.temperature(),
                true,
                settings.maxTokens(),
                true,
                model
        ).withFormat(FormatPresets.SIMPLE);
        return http.exchangeRaw(wrapped, overrides, provider.url(), firstKey(provider), model).text();
    }

    private static String firstKey(ProviderSettings provider) {
        for (String key : provider.apiKeys()) {
            if (key != null && !key.isBlank()) {
                return key.trim();
            }
        }
        return "";
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    public enum Skip {
        CHECKED, DISABLED, BYPASS, TOO_SHORT, COOLDOWN, MINUTE_CAP, DAILY_CAP, UNAVAILABLE
    }

    public record Decision(Skip skip, boolean flagged, String category, String reason) {
        public static Decision skipped(Skip skip) {
            return new Decision(skip, false, "none", "");
        }
    }
}
