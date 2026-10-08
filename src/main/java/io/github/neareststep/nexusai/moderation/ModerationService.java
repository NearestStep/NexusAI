package io.github.neareststep.nexusai.moderation;

import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.OpenAiProvider;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.ReasoningModels;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.budget.QuotaPolicy;
import io.github.neareststep.nexusai.budget.TokenAccounting;
import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.ModerationSettings;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;
import io.github.neareststep.nexusai.event.EventDispatcher;

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
    private volatile TokenAccounting accounting = TokenAccounting.none();
    private volatile QuotaPolicy quotas;

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

    /** Server and row caps. Moderation does not spend the player cap. */
    public void quotas(QuotaPolicy policy) {
        this.quotas = policy;
    }

    /** Counts a moderation HTTP attempt. The player slice is not used for this origin. */
    public void tokenAccounting(TokenAccounting accounting) {
        this.accounting = accounting == null ? TokenAccounting.none() : accounting;
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
        String wrapped = PlayerInput.wrap(text);
        QuotaPolicy.Decision serverQuota = reserveServer(wrapped);
        if (serverQuota != null && !serverQuota.allowed()) {
            gate.release(playerId, now);
            return Decision.skipped(Skip.DAILY_CAP);
        }
        Optional<ReservedRow> choice = reserveDaily(now, wrapped);
        if (choice.isEmpty()) {
            if (serverQuota != null) {
                serverQuota.hold().releaseWith(quotas);
            }
            gate.release(playerId, now);
            return Decision.skipped(dailyBlocked(now) || queue.allRowsTokenBlocked(now)
                    ? Skip.DAILY_CAP : Skip.UNAVAILABLE);
        }
        queue.recordModerationCheck();
        String reply;
        try {
            reply = call(playerId, wrapped, choice.get().choice);
        } catch (AiRequestException e) {
            logger.log(Level.FINE, "Chat moderation request failed: "
                    + SecretMask.redact(e.getMessage(), config.configuredSecrets()));
            return new Decision(Skip.CHECKED, false, "none", "");
        } catch (RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logger.log(Level.FINE, "Chat moderation request failed: "
                    + SecretMask.redact(detail, config.configuredSecrets()));
            return new Decision(Skip.CHECKED, false, "none", "");
        } finally {
            if (serverQuota != null) {
                serverQuota.hold().releaseWith(quotas);
            }
            QuotaPolicy.Decision row = choice.get().row;
            if (row != null) {
                row.hold().releaseWith(quotas);
            }
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
        EventDispatcher.get().moderationFlag(playerId, playerName, text, verdict.category(), verdict.reason());
        notifier.flagged(playerName == null ? "" : playerName, text, verdict.category(), verdict.reason());
        logger.info("Chat moderation flagged " + (playerName == null ? "a player" : playerName)
                + " (" + verdict.category() + ")"
                + (verdict.reason().isBlank() ? "" : ": " + verdict.reason()));
        return new Decision(Skip.CHECKED, true, verdict.category(), verdict.reason());
    }

    private QuotaPolicy.Decision reserveServer(String wrapped) {
        QuotaPolicy policy = quotas;
        if (policy == null) {
            return null;
        }
        String model = settings.pinned() ? settings.model() : config.getModel();
        return policy.tryReserve(new QuotaPolicy.Charge(
                RequestOrigin.MODERATION, "nexusai", null, null, estimate(wrapped, model), null));
    }

    private Optional<ReservedRow> reserveDaily(long now, String wrapped) {
        if (!settings.pinned()) {
            Optional<ModelQueue.Choice> selected = queue.select(now);
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            ProviderSettings provider = config.provider(selected.get().provider());
            if (!canCall(provider)) {
                return Optional.empty();
            }
            QuotaPolicy.Decision row = reserveRow(selected.get(), wrapped);
            if (row != null && !row.allowed()) {
                return Optional.empty();
            }
            if (!queue.tryConsume(selected.get().index(), now)) {
                if (row != null) {
                    row.hold().releaseWith(quotas);
                }
                return Optional.empty();
            }
            return Optional.of(new ReservedRow(selected.get(), row));
        }
        ProviderSettings provider = config.provider(settings.provider());
        if (!canCall(provider)) {
            return Optional.empty();
        }
        for (ModelQueue.Status row : queue.status(now)) {
            if (!row.provider().equals(settings.provider()) || !row.model().equals(settings.model())) {
                continue;
            }
            ModelQueue.Choice choice = new ModelQueue.Choice(row.index(), row.provider(), row.model());
            QuotaPolicy.Decision reserved = reserveRow(choice, wrapped);
            if (reserved != null && !reserved.allowed()) {
                return Optional.empty();
            }
            if (!queue.tryConsume(row.index(), now)) {
                if (reserved != null) {
                    reserved.hold().releaseWith(quotas);
                }
                return Optional.empty();
            }
            return Optional.of(new ReservedRow(choice, reserved));
        }
        return Optional.of(new ReservedRow(new ModelQueue.Choice(-1, settings.provider(), settings.model()), null));
    }

    private QuotaPolicy.Decision reserveRow(ModelQueue.Choice choice, String wrapped) {
        QuotaPolicy policy = quotas;
        if (policy == null || choice == null || choice.index() < 0) {
            return null;
        }
        return policy.tryReserveRow(
                queue.rowStorageId(choice.index()),
                queue.dailyTokenLimit(choice.index()),
                estimate(wrapped, choice.model()));
    }

    private long estimate(String wrapped, String model) {
        int chars = ResponseUsage.chars(settings.systemPrompt()) + ResponseUsage.chars(wrapped);
        Integer maxTokens = settings.maxTokens() > 0 ? settings.maxTokens() : null;
        return QuotaEstimates.tokens(chars, maxTokens, ReasoningModels.isReasoning(model));
    }

    private record ReservedRow(ModelQueue.Choice choice, QuotaPolicy.Decision row) {
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

    private String call(UUID playerId, String wrapped, ModelQueue.Choice choice) {
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
        CallTrace trace = CallTrace.start(RequestOrigin.MODERATION, playerId, "", "");
        boolean dedicated = choice.index() < 0;
        try {
            io.github.neareststep.nexusai.ai.ChatExchange exchange = http.exchangeRaw(
                    wrapped, overrides, provider.url(), firstKey(provider), model, trace);
            accounting.record(exchange.usage(), trace, providerId, choice.index(), dedicated, queue, model);
            return exchange.text();
        } catch (AiRequestException error) {
            accounting.record(error.usage(), trace, providerId, choice.index(), dedicated, queue, model);
            EventDispatcher.get().providerError(trace, providerId, model, error, false);
            throw error;
        }
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
