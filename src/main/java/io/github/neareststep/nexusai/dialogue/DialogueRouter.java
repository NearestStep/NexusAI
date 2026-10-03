package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.KeyRing;
import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.LengthTrimNotices;
import io.github.neareststep.nexusai.budget.ModelQueue;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.ProviderSettings;
import io.github.neareststep.nexusai.config.SecretMask;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        if (rejected.isPresent()) {
            throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, rejected.get(), null);
        }
        long now = clock.getAsLong();
        // selectable() advances the player-reply round-robin cursor.
        // A summary uses its own cursor. A pin must not take either turn.
        List<ModelQueue.Choice> choices = call.summaryPinned()
                ? pin(currentQueue, call, now)
                : call.kind() == DialogueEngine.CallKind.SUMMARY
                ? currentQueue.selectableSummary(now)
                : currentQueue.selectable(now, false);
        if (choices.isEmpty()) {
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
                        admission.success(admissionKey);
                        String content = sent.result().content();
                        return new DialogueEngine.ModelReply(content == null ? "" : content, List.of(), false);
                    }
                    if (!sent.result().toolNames().isEmpty() && !toolsDropped) {
                        admission.success(admissionKey);
                        return new DialogueEngine.ModelReply(sent.result().content(), sent.result().toolNames(), false);
                    }
                    String text = safeText(current, call, sent.result().content(), key, sent.result().finishReason());
                    if (LengthCutoff.isLength(sent.result().finishReason())) {
                        LengthTrimNotices.note(logger, talkNotice(call));
                    }
                    admission.success(admissionKey);
                    return new DialogueEngine.ModelReply(text, List.of(), toolsDropped);
                } catch (AiRequestException error) {
                    last = error;
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
        if (last != null && last.kind() != AiErrorKind.REJECTED && last.kind() != AiErrorKind.LOCAL_LIMIT) {
            admission.failure(admissionKey, last);
        }
        if (last != null && last.kind() == AiErrorKind.REJECTED) {
            throw last;
        }
        throw currentQueue.explain(last, clock.getAsLong());
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
            ));
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
            String finishReason
    ) {
        return transport.finishText(raw, call.wrappedUser(), call.formatId(), apiKey, finishReason);
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
    }
}
