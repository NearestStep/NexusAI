package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.LengthTrimNotices;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.FallbackModel;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends a dialogue completion through the shared model queue and key ring.
 * A provider that rejects tools is retried once without tools. That retry does not parse actions out of text.
 */
public final class DialogueRouter {

    private final Function<String, PluginConfig> config;
    private final Function<String, ModelQueue> queue;
    private final Function<String, KeyRing> rings;
    private final DialogueTransport transport;
    private final Admission admission;
    private final Logger logger;
    private final LongSupplier clock;

    public DialogueRouter(
            Function<String, PluginConfig> config,
            Function<String, ModelQueue> queue,
            Function<String, KeyRing> rings,
            DialogueTransport transport,
            Admission admission,
            Logger logger,
            LongSupplier clock
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.rings = Objects.requireNonNull(rings, "rings");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public DialogueEngine.ModelReply route(DialogueEngine.ModelCall call) {
        PluginConfig current = config.apply("");
        ModelQueue currentQueue = queue.apply("");
        if (current == null || currentQueue == null) {
            throw new AiRequestException(AiErrorKind.OTHER, 0, "NexusAI is not ready", null);
        }
        if (!current.canSendChatRequests()) {
            throw new AiRequestException(AiErrorKind.OTHER, 0, "NexusAI API key is not configured", null);
        }
        String admissionKey = admissionKey(call);
        Optional<String> rejected = admission.admit(call.playerId(), admissionKey);
        boolean keepPause = false;
        if (rejected.isPresent()) {
            if (canTalkDuringPause(current, currentQueue, call)) {
                Optional<String> limited = admission.admitIgnoringPause(call.playerId(), admissionKey);
                if (limited.isPresent()) {
                    throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, limited.get(), null);
                }
                keepPause = true;
            } else {
                throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejected.get(), null);
            }
        }
        long now = clock.getAsLong();
        // selectable() advances the player-reply round-robin cursor.
        // A summary uses its own cursor. A pin must not take either turn.
        List<ModelQueue.Choice> choices = call.summaryPinned()
                ? pin(currentQueue, call, now)
                : call.kind() == DialogueEngine.CallKind.SUMMARY
                ? currentQueue.selectableSummary(now)
                : currentQueue.selectable(now, false);
        if (keepPause) {
            choices = withoutPausedProviders(choices, currentQueue, now);
        }
        if (choices.isEmpty()) {
            DialogueEngine.ModelReply fallbackReply = talkFallback(
                    current, currentQueue, call, admissionKey, null, keepPause, new boolean[1]);
            if (fallbackReply != null) {
                return fallbackReply;
            }
            if (keepPause && rejected.isPresent()) {
                throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejected.get(), null);
            }
            throw currentQueue.explain(null, now);
        }
        if (transport.saturated()) {
            throw HttpPool.queueFull(null);
        }
        AiRequestException last = null;
        boolean toolsDropped = false;
        for (ModelQueue.Choice choice : choices) {
            ProviderSettings provider = current.provider(choice.provider());
            if (provider == null) {
                last = new AiRequestException(AiErrorKind.OTHER, 0, "Unknown provider " + choice.provider(), null);
                currentQueue.markFailure(choice.index(), last, clock.getAsLong());
                continue;
            }
            GenerationOverrides overrides = call.overrides() == null ? GenerationOverrides.none() : call.overrides();
            String model = overrides.modelOverridden() ? overrides.model(choice.model()) : choice.model();
            KeyRing ring = rings.apply(provider.id());
            if (ring == null) {
                ring = new KeyRing(provider.apiKeys());
            }
            int attempts = Math.max(1, ring.keys().size());
            for (int attempt = 0; attempt < attempts; attempt++) {
                now = clock.getAsLong();
                String key = ring.acquire(now, false);
                if (key == null) {
                    currentQueue.cooldown(choice.index(), Math.max(now + 1_000L, ring.nextReadyAt(now)), ModelQueue.Hold.ERROR);
                    break;
                }
                if (!provider.hasKeys() && !current.providerAllowsKeyless(provider)) {
                    last = new AiRequestException(AiErrorKind.OTHER, 0, "API key is not configured", null);
                    break;
                }
                if (choice.index() >= 0 && !currentQueue.tryConsume(choice.index(), now)) {
                    break;
                }
                try {
                    SendOnce sent = send(current, call, provider.url(), key, model, call.tools());
                    toolsDropped = false;
                    if (sent.unsupportedTools()) {
                        toolsDropped = true;
                        sent = send(current, call, provider.url(), key, model, List.of());
                    }
                    if (sent.result() == null) {
                        throw new AiRequestException(AiErrorKind.OTHER, 0, "Provider does not support tools", null);
                    }
                    if (choice.index() >= 0) {
                        currentQueue.observe(choice.index(), sent.result().headers(), clock.getAsLong());
                    }
                    if (call.kind() == DialogueEngine.CallKind.SUMMARY) {
                        noteSuccess(admissionKey, keepPause);
                        String content = SecretMask.redact(
                                sent.result().content() == null ? "" : sent.result().content(),
                                summarySecrets(current, key));
                        return new DialogueEngine.ModelReply(content, List.of(), false);
                    }
                    return spokenReply(current, call, key, sent, toolsDropped, admissionKey, keepPause);
                } catch (AiRequestException error) {
                    last = error.withPausedProvider(choice.provider());
                    now = clock.getAsLong();
                    if (HttpPool.isQueueFull(error)) {
                        throw HttpPool.queueFull(error);
                    }
                    if (error.kind() == AiErrorKind.MARKUP_ONLY) {
                        log(call, Level.FINE, SecretMask.redact(error.getMessage(), current.configuredSecrets()));
                        last = error;
                        break;
                    }
                    if (error.kind() == AiErrorKind.EMPTY_REPLY) {
                        log(call, Level.WARNING, SecretMask.redact(error.getMessage(), current.configuredSecrets()));
                        last = error;
                        break;
                    }
                    if (error.kind() == AiErrorKind.REJECTED) {
                        if (choice.index() >= 0) {
                            currentQueue.recordRejection(choice.index());
                        }
                        log(call, Level.INFO, "Rejected dialogue answer from " + choice.provider() + " / " + model
                                + ". " + SecretMask.redact(error.getMessage(), current.configuredSecrets()));
                        break;
                    }
                    if (!key.isEmpty() && (error.kind() == AiErrorKind.BAD_KEY || error.kind() == AiErrorKind.RATE_LIMIT)) {
                        long skipFor = error.kind() == AiErrorKind.BAD_KEY
                                ? current.getAuthPauseSeconds() * 1000L
                                : Math.max(current.getProviderPauseSeconds() * 1000L, error.retryAfterSeconds() * 1000L);
                        ring.skip(key, now + skipFor);
                        log(call, Level.WARNING, "Dialogue call skipped key " + SecretMask.mask(key)
                                + " on " + choice.provider() + " after HTTP " + error.status());
                    }
                    boolean anotherKey = !key.isEmpty() && error.kind() == AiErrorKind.BAD_KEY && ring.hasAvailable(now);
                    if (anotherKey) {
                        continue;
                    }
                    currentQueue.markFailure(choice.index(), error, now);
                    break;
                }
            }
        }
        if (last != null && (last.kind() == AiErrorKind.EMPTY_REPLY || last.kind() == AiErrorKind.MARKUP_ONLY)) {
            throw last;
        }
        if (last != null && last.kind() == AiErrorKind.REJECTED) {
            throw last;
        }
        boolean[] fallbackRecorded = {false};
        DialogueEngine.ModelReply fallbackReply = talkFallback(
                current, currentQueue, call, admissionKey, last, keepPause, fallbackRecorded);
        if (fallbackReply != null) {
            return fallbackReply;
        }
        if (!fallbackRecorded[0] && last != null && last.kind() != AiErrorKind.LOCAL_LIMIT) {
            admission.failure(admissionKey, last);
        }
        if (keepPause && rejected.isPresent()) {
            throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejected.get(), null);
        }
        throw currentQueue.explain(last, clock.getAsLong());
    }

    private static List<String> summarySecrets(PluginConfig current, String key) {
        List<String> secrets = new ArrayList<>();
        if (key != null && !key.isBlank()) {
            secrets.add(key.trim());
        }
        for (String configured : current.configuredSecrets()) {
            if (configured != null && !configured.isBlank() && !secrets.contains(configured)) {
                secrets.add(configured);
            }
        }
        return secrets;
    }

    private void noteSuccess(String admissionKey, boolean keepPause) {
        if (keepPause) {
            admission.successKeepingPause(admissionKey);
        } else {
            admission.success(admissionKey);
        }
    }

    /**
     * Talk may leave a paused queue provider when {@code fallback-model} is a different provider
     * that is not paused. A global pause, a summary, and a fallback on the paused provider stay busy.
     */
    private boolean canTalkDuringPause(PluginConfig current, ModelQueue currentQueue, DialogueEngine.ModelCall call) {
        if (call.kind() == DialogueEngine.CallKind.SUMMARY || call.summaryPinned()) {
            return false;
        }
        if (!admission.providerPauseActive() || admission.pauseIsGlobal()) {
            return false;
        }
        long now = clock.getAsLong();
        if (hasUnpausedQueueProvider(currentQueue, now)) {
            return true;
        }
        return pauseFallbackEligible(current, currentQueue, call, now);
    }

    private DialogueEngine.ModelReply talkFallback(
            PluginConfig current,
            ModelQueue currentQueue,
            DialogueEngine.ModelCall call,
            String admissionKey,
            AiRequestException last,
            boolean keepPause,
            boolean[] failureRecorded
    ) {
        if (call.kind() == DialogueEngine.CallKind.SUMMARY || call.summaryPinned()) {
            return null;
        }
        long now = clock.getAsLong();
        boolean pausing = keepPause
                || admission.providerPauseActive()
                || (last != null && last.kind().pausesProvider())
                || queuePaused(currentQueue, now);
        if (!pausing || !pauseFallbackEligible(current, currentQueue, call, now)) {
            return null;
        }
        FallbackModel fallback = resolveFallback(current, call);
        ModelQueue.FallbackPlan plan = currentQueue.planFallback(
                fallback.provider(), fallback.model(), now, false, Set.of());
        if (!plan.allowed()) {
            return null;
        }
        ProviderSettings provider = current.provider(plan.provider());
        if (provider == null) {
            return null;
        }
        if (!provider.hasKeys() && !current.providerAllowsKeyless(provider)) {
            return null;
        }
        KeyRing ring = rings.apply(provider.id());
        if (ring == null) {
            ring = new KeyRing(provider.apiKeys());
        }
        String model = plan.model();
        AiRequestException fallbackError = null;
        int attempts = Math.max(1, ring.keys().size());
        for (int attempt = 0; attempt < attempts; attempt++) {
            now = clock.getAsLong();
            String key = ring.acquire(now, false);
            if (key == null) {
                break;
            }
            boolean consumed = plan.dedicated()
                    ? currentQueue.tryConsumeFallback(plan.provider(), plan.model(), now)
                    : currentQueue.tryConsume(plan.queueIndex(), now);
            if (!consumed) {
                break;
            }
            try {
                SendOnce sent = send(current, call, provider.url(), key, model, call.tools());
                if (sent.unsupportedTools()) {
                    sent = send(current, call, provider.url(), key, model, List.of());
                }
                if (sent.result() == null) {
                    break;
                }
                if (plan.dedicated()) {
                    currentQueue.observeFallback(plan.provider(), plan.model(), sent.result().headers(), clock.getAsLong());
                } else if (plan.queueIndex() >= 0) {
                    currentQueue.observe(plan.queueIndex(), sent.result().headers(), clock.getAsLong());
                }
                return spokenReply(current, call, key, sent, sent.unsupportedTools(), admissionKey, keepPause);
            } catch (AiRequestException error) {
                AiRequestException tagged = error.withPausedProvider(plan.provider());
                if (last != null) {
                    for (String paused : AiRequestException.pausedProvidersOf(last)) {
                        tagged = tagged.withPausedProvider(paused);
                    }
                }
                fallbackError = tagged;
                now = clock.getAsLong();
                if (!key.isEmpty() && (tagged.kind() == AiErrorKind.BAD_KEY || tagged.kind() == AiErrorKind.RATE_LIMIT)) {
                    long skipFor = tagged.kind() == AiErrorKind.BAD_KEY
                            ? current.getAuthPauseSeconds() * 1000L
                            : Math.max(current.getProviderPauseSeconds() * 1000L, tagged.retryAfterSeconds() * 1000L);
                    ring.skip(key, now + skipFor);
                    log(call, Level.WARNING, "Dialogue call skipped key " + SecretMask.mask(key)
                            + " on " + plan.provider() + " after HTTP " + tagged.status());
                }
                if (tagged.kind() == AiErrorKind.MARKUP_ONLY) {
                    log(call, Level.FINE, SecretMask.redact(tagged.getMessage(), current.configuredSecrets()));
                    throw tagged;
                }
                if (tagged.kind() == AiErrorKind.EMPTY_REPLY) {
                    log(call, Level.WARNING, SecretMask.redact(tagged.getMessage(), current.configuredSecrets()));
                    throw tagged;
                }
                if (tagged.kind() == AiErrorKind.REJECTED) {
                    if (plan.dedicated()) {
                        currentQueue.recordFallbackRejection(plan.provider(), plan.model());
                    } else if (plan.queueIndex() >= 0) {
                        currentQueue.recordRejection(plan.queueIndex());
                    }
                    log(call, Level.INFO, "Rejected dialogue answer from " + plan.provider() + " / " + model
                            + ". " + SecretMask.redact(tagged.getMessage(), current.configuredSecrets()));
                    throw tagged;
                }
                if (HttpPool.isQueueFull(tagged)) {
                    throw HttpPool.queueFull(tagged);
                }
                boolean anotherKey = !key.isEmpty() && tagged.kind() == AiErrorKind.BAD_KEY && ring.hasAvailable(now);
                if (anotherKey) {
                    continue;
                }
                break;
            }
        }
        if (fallbackError == null) {
            return null;
        }
        now = clock.getAsLong();
        if (plan.dedicated()) {
            currentQueue.markFallbackFailure(plan.provider(), plan.model(), fallbackError, now);
        } else {
            currentQueue.markFailure(plan.queueIndex(), fallbackError, now);
        }
        admission.failure(admissionKey, fallbackError);
        if (failureRecorded != null && failureRecorded.length > 0) {
            failureRecorded[0] = true;
        }
        throw fallbackError;
    }

    /**
     * A tool-call branch runs only when this turn offered tools. An unsolicited {@code tool_calls}
     * list is ordinary text and goes through {@link #safeText}. Content shown with a real tool call
     * is sanitized the same way before it can reach the player or dialogue memory.
     */
    private DialogueEngine.ModelReply spokenReply(
            PluginConfig current,
            DialogueEngine.ModelCall call,
            String key,
            SendOnce sent,
            boolean toolsDropped,
            String admissionKey,
            boolean keepPause
    ) {
        boolean toolsOffered = call.tools() != null && !call.tools().isEmpty();
        if (toolsOffered && !sent.result().toolNames().isEmpty() && !toolsDropped) {
            String shown = sanitizeAlongsideTools(
                    current, call, sent.result().content(), key, sent.result().finishReason(), sent.result().usage());
            noteSuccess(admissionKey, keepPause);
            return new DialogueEngine.ModelReply(shown, sent.result().toolNames(), false);
        }
        String text = safeText(
                current, call, sent.result().content(), key, sent.result().finishReason(), sent.result().usage());
        if (LengthCutoff.isLength(sent.result().finishReason())) {
            LengthTrimNotices.note(logger, talkNotice(call));
        }
        noteSuccess(admissionKey, keepPause);
        return new DialogueEngine.ModelReply(text, List.of(), toolsDropped);
    }

    private String sanitizeAlongsideTools(
            PluginConfig current,
            DialogueEngine.ModelCall call,
            String content,
            String key,
            String finishReason,
            ResponseUsage usage
    ) {
        if (content == null || content.isBlank()) {
            return content == null ? "" : content;
        }
        return safeText(current, call, content, key, finishReason, usage);
    }

    private boolean pauseFallbackEligible(
            PluginConfig current,
            ModelQueue currentQueue,
            DialogueEngine.ModelCall call,
            long now
    ) {
        if (admission.pauseIsGlobal()) {
            return false;
        }
        FallbackModel fallback = resolveFallback(current, call);
        if (fallback == null) {
            return false;
        }
        if (providerPaused(currentQueue, fallback.provider(), now)) {
            return false;
        }
        Set<String> pausedQueue = pausedQueueProviders(currentQueue, now);
        if (pausedQueue.isEmpty() && !admission.providerPauseActive()) {
            return false;
        }
        if (pausedQueue.contains(fallback.provider())) {
            return false;
        }
        if (hasUnpausedQueueProvider(currentQueue, now)) {
            return false;
        }
        return currentQueue.planFallback(fallback.provider(), fallback.model(), now, false, Set.of()).allowed();
    }

    private boolean hasUnpausedQueueProvider(ModelQueue currentQueue, long now) {
        for (ModelQueue.Status row : currentQueue.status(now)) {
            if (row.state() == null) {
                continue;
            }
            if (!row.state().startsWith("ACTIVE") && !row.state().startsWith("AVAILABLE")) {
                continue;
            }
            if (!providerPaused(currentQueue, row.provider(), now)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> pausedQueueProviders(ModelQueue currentQueue, long now) {
        Set<String> paused = new LinkedHashSet<>();
        for (ModelQueue.Status row : currentQueue.status(now)) {
            if (providerPaused(currentQueue, row.provider(), now)) {
                paused.add(row.provider());
            }
        }
        return paused;
    }

    private boolean queuePaused(ModelQueue currentQueue, long now) {
        return !pausedQueueProviders(currentQueue, now).isEmpty();
    }

    private boolean providerPaused(ModelQueue currentQueue, String providerId, long now) {
        if (admission.providerPaused(providerId)) {
            return true;
        }
        if (currentQueue.providerPauseHold(providerId, now)) {
            return true;
        }
        KeyRing ring = rings.apply(providerId);
        return ring != null && !ring.isEmpty() && !ring.hasAvailable(now);
    }

    private List<ModelQueue.Choice> withoutPausedProviders(List<ModelQueue.Choice> choices, ModelQueue currentQueue, long now) {
        List<ModelQueue.Choice> open = new ArrayList<>();
        for (ModelQueue.Choice choice : choices) {
            if (!providerPaused(currentQueue, choice.provider(), now)) {
                open.add(choice);
            }
        }
        return open;
    }

    private FallbackModel resolveFallback(PluginConfig current, DialogueEngine.ModelCall call) {
        GenerationOverrides overrides = call.overrides() == null ? GenerationOverrides.none() : call.overrides();
        FallbackModel fromCall = overrides.fallbackModel();
        if (fromCall != null && fromCall.configured()) {
            return fromCall;
        }
        FallbackModel global = current.fallbackModel();
        return global != null && global.configured() ? global : null;
    }

    private SendOnce send(
            PluginConfig current,
            DialogueEngine.ModelCall call,
            String baseUrl,
            String apiKey,
            String model,
            List<CharacterAction> tools
    ) {
        GenerationOverrides overrides = call.overrides() == null ? GenerationOverrides.none() : call.overrides();
        DialogueProtocol.TokenBudget budget = DialogueProtocol.tokens(
                model,
                overrides.maxTokens(current.getMaxTokens()),
                current.getReasoningEffort()
        );
        Double temperature = overrides.temperature(current.getTemperature());
        if (Reasoning(model) && ReasoningModelsUsesCompletion(model)) {
            temperature = null;
        }
        try {
            DialogueTransport.Result result = transport.send(new DialogueTransport.Request(
                    baseUrl,
                    apiKey,
                    model,
                    call.system(),
                    call.messages(),
                    tools,
                    temperature,
                    budget.maxTokens(),
                    budget.maxCompletionTokens(),
                    budget.reasoningEffort(),
                    current.getReadTimeout()
            ), call.trace());
            return new SendOnce(result, false);
        } catch (AiRequestException error) {
            if (error.unsupportedTools() && tools != null && !tools.isEmpty()) {
                return new SendOnce(null, true);
            }
            throw error;
        }
    }

    private String safeText(
            PluginConfig current,
            DialogueEngine.ModelCall call,
            String raw,
            String apiKey,
            String finishReason,
            ResponseUsage usage
    ) {
        return transport.finishText(raw, call.wrappedUser(), call.formatId(), apiKey, finishReason, usage);
    }

    /**
     * The persona name identifies the talk. The rendered character sheet is not logged.
     * A blank id still names the command.
     */
    private static String admissionKey(DialogueEngine.ModelCall call) {
        if (call.kind() == DialogueEngine.CallKind.SUMMARY) {
            return "dialogue:summary";
        }
        return "dialogue:" + (call.messages().isEmpty() ? "greeting" : "turn");
    }

    /**
     * Pinned summary calls follow {@code ModerationService.reserveDaily}: a queue row on cooldown
     * or over its daily cap is not called. A provider and model that are not in the queue are
     * called directly. This does not call {@link ModelQueue#selectable(long, boolean)}, so a pin
     * does not advance the round-robin cursor.
     */
    private static List<ModelQueue.Choice> pin(ModelQueue queue, DialogueEngine.ModelCall call, long now) {
        for (ModelQueue.Status row : queue.status(now)) {
            if (!call.pinProvider().equals(row.provider()) || !call.pinModel().equals(row.model())) {
                continue;
            }
            String state = row.state() == null ? "" : row.state();
            if (state.startsWith("LIMIT REACHED") || state.startsWith("COOLDOWN")) {
                throw queue.explain(null, now);
            }
            return List.of(new ModelQueue.Choice(row.index(), row.provider(), row.model()));
        }
        return List.of(new ModelQueue.Choice(-1, call.pinProvider(), call.pinModel()));
    }

    private void log(DialogueEngine.ModelCall call, Level level, String message) {
        if (message == null) {
            return;
        }
        if (call.kind() == DialogueEngine.CallKind.SUMMARY && level.intValue() > Level.FINE.intValue()) {
            logger.fine(message);
            return;
        }
        logger.log(level, message);
    }

    private static String talkNotice(DialogueEngine.ModelCall call) {
        GenerationOverrides overrides = call.overrides();
        String id = overrides == null ? null : overrides.noticeId();
        if (id == null || id.isBlank()) {
            return "nai talk";
        }
        return id;
    }

    private static boolean Reasoning(String model) {
        return io.github.neareststep.nexusai.ai.ReasoningModels.isReasoning(model);
    }

    private static boolean ReasoningModelsUsesCompletion(String model) {
        return io.github.neareststep.nexusai.ai.ReasoningModels.usesCompletionTokenCap(model);
    }

    private record SendOnce(DialogueTransport.Result result, boolean unsupportedTools) {
    }

    public interface Admission {
        Optional<String> admit(UUID playerId, String key);

        void success(String key);

        void failure(String key, Throwable error);

        /**
         * True when a provider pause is in effect. The default is false, so tests that only
         * implement {@link #admit} keep the 1.1.0 block.
         */
        default boolean providerPauseActive() {
            return false;
        }

        /**
         * True when the active pause names no provider and therefore covers every provider.
         */
        default boolean pauseIsGlobal() {
            return providerPauseActive();
        }

        /**
         * True when {@code providerId} is inside the current pause.
         * A global pause pauses every id. No pause pauses none.
         */
        default boolean providerPaused(String providerId) {
            return providerPauseActive();
        }

        /**
         * Rate limits and per-prompt backoff, without the provider pause.
         * The default refuses, so a pause stays a pause unless the caller opts in.
         */
        default Optional<String> admitIgnoringPause(UUID playerId, String key) {
            return Optional.of("Provider requests are paused");
        }

        /**
         * Records a successful talk that used a provider the pause does not cover.
         * Must not clear that pause.
         */
        default void successKeepingPause(String key) {
        }
    }
}
