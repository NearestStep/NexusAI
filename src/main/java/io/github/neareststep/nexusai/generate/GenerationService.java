package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiErrors;
import io.github.neareststep.nexusai.ai.AiHttpClient;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.LengthCutoff;
import io.github.neareststep.nexusai.ai.ModelAnswer;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.ai.RoutingProvider;
import io.github.neareststep.nexusai.ai.SharedCompletion;
import io.github.neareststep.nexusai.api.CacheMode;
import io.github.neareststep.nexusai.api.ContextRequest;
import io.github.neareststep.nexusai.api.GenerationError;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.GenerationRequestFacts;
import io.github.neareststep.nexusai.api.GenerationResult;
import io.github.neareststep.nexusai.api.JsonGenerationResult;
import io.github.neareststep.nexusai.api.JsonSchema;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.StructuredMode;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.ResultSource;
import io.github.neareststep.nexusai.api.QuotaStatus;
import io.github.neareststep.nexusai.api.TokenUsage;
import io.github.neareststep.nexusai.budget.QuotaEstimates;
import io.github.neareststep.nexusai.budget.QuotaGroups;
import io.github.neareststep.nexusai.budget.QuotaPolicy;
import io.github.neareststep.nexusai.cache.AiCache;
import io.github.neareststep.nexusai.config.FormatPresets;
import io.github.neareststep.nexusai.event.EventDispatcher;
import io.github.neareststep.nexusai.event.GenerationEvents;
import io.github.neareststep.nexusai.event.PreCancelled;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.config.LogRedaction;
import io.github.neareststep.nexusai.config.PluginConfig;
import io.github.neareststep.nexusai.config.SecretMask;
import io.github.neareststep.nexusai.context.ContextBlock;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.json.JsonDocuments;
import io.github.neareststep.nexusai.json.JsonRepair;
import io.github.neareststep.nexusai.json.StructuredOutputSupport;
import io.github.neareststep.nexusai.knowledge.KnowledgeBase;
import io.github.neareststep.nexusai.knowledge.KnowledgeComposer;
import io.github.neareststep.nexusai.knowledge.KnowledgeRequest;
import io.github.neareststep.nexusai.knowledge.KnowledgeRequests;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import io.github.neareststep.nexusai.prompt.ResolvedPrompt;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link io.github.neareststep.nexusai.api.NexusAIApi#generate} pipeline.
 * One volatile {@link GenerationRuntime} is captured per call. Reload publishes a replacement
 * and does not cancel work that already started.
 */
@ApiStatus.Internal
public final class GenerationService {

    private final Plugin plugin;
    private final ExecutorService http;
    private final ExecutorService scheduler;
    private final Logger logger;
    private final RegionTasks regions;
    private final PlayerStateReader reader;
    private final GenerationHooks hooks;
    private final ConcurrentHashMap<CompletableFuture<GenerationResult>, CallTrace> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompletableFuture<GenerationResult>, JsonSession> jsonSessions = new ConcurrentHashMap<>();
    private volatile GenerationRuntime runtime;
    private volatile QuotaPolicy quotas;
    private volatile boolean closed;

    /** Runs on the worker when a second call has joined an in-flight request. Null in production. */
    static volatile Runnable onInFlightJoin;

    public GenerationService(Plugin plugin, ExecutorService http, ExecutorService scheduler, Logger logger) {
        this(plugin, http, scheduler, logger, new BukkitRegions(), new BukkitPlayerState(), new GenerationHooks());
    }

    public GenerationService(
            Plugin plugin,
            ExecutorService http,
            ExecutorService scheduler,
            Logger logger,
            RegionTasks regions,
            PlayerStateReader reader,
            GenerationHooks hooks
    ) {
        this.plugin = plugin;
        this.http = http;
        this.scheduler = scheduler;
        this.logger = logger == null ? Logger.getLogger("NexusAI") : logger;
        this.regions = regions == null ? new BukkitRegions() : regions;
        this.reader = reader == null ? new BukkitPlayerState() : reader;
        this.hooks = hooks == null ? new GenerationHooks() : hooks;
    }

    /** Daily caps for API calls. A null policy leaves quotas off. */
    public void quotas(QuotaPolicy policy) {
        this.quotas = policy;
    }

    /**
     * Today's spend and caps for one API plugin. Memory only. Empty caps when quotas are off
     * or this service has no policy yet.
     */
    public QuotaStatus quota(String pluginName) {
        QuotaPolicy policy = quotas;
        if (policy == null) {
            return QuotaStatus.empty(java.time.LocalDate.now());
        }
        return policy.consumerStatus(pluginName);
    }

    public void publish(GenerationRuntime next) {
        if (!closed && next != null) {
            this.runtime = next;
        }
    }

    /** Enabled, not held, and {@code plugin-api.enabled}. Does not require an API key. */
    public boolean available() {
        if (closed) {
            return false;
        }
        GenerationRuntime current = runtime;
        if (current == null) {
            return false;
        }
        PluginConfig config = current.config();
        return config.pluginApiEnabled() && !config.requestsHeld();
    }

    public boolean accepting() {
        return !closed && runtime != null;
    }

    /**
     * Completes every pending future with {@link NexusErrorKind#SHUTDOWN} on a NexusAI thread.
     * Call this before the HTTP executor is shut down.
     */
    public void shutdown() {
        closed = true;
        Runnable task = () -> {
            for (Map.Entry<CompletableFuture<GenerationResult>, CallTrace> entry : new ArrayList<>(pending.entrySet())) {
                deliver(entry.getKey(), entry.getValue(), shutdownResult(entry.getValue()), true);
            }
        };
        try {
            http.execute(task);
        } catch (RejectedExecutionException rejected) {
            try {
                if (scheduler != null) {
                    scheduler.execute(task);
                    return;
                }
            } catch (RejectedExecutionException ignored) {
                // Fall through and complete here. The executors are already stopped.
            }
            task.run();
        }
    }

    public CompletableFuture<GenerationResult> generate(Plugin owner, GenerationRequest request) {
        return generate(owner, request, null);
    }

    /**
     * {@link io.github.neareststep.nexusai.api.NexusAIApi#generateJson(Plugin, GenerationRequest, JsonSchema)}.
     * The future completes on a NexusAI thread, except {@code NexusAI is not enabled}, which may
     * complete on the caller.
     */
    public CompletableFuture<JsonGenerationResult> generateJson(Plugin owner, GenerationRequest request, JsonSchema schema) {
        if (owner == null || request == null || schema == null) {
            throw new IllegalArgumentException("owner, request, and schema are required");
        }
        JsonSession session = new JsonSession(schema);
        CompletableFuture<GenerationResult> inner = generate(owner, request, session);
        CompletableFuture<JsonGenerationResult> outer = new CompletableFuture<>();
        inner.whenComplete((result, error) -> {
            if (error != null) {
                outer.completeExceptionally(unwrap(error));
                return;
            }
            outer.complete(session.toResult(result));
        });
        return outer;
    }

    /**
     * Schema from the registered prompt. A prompt with no {@link JsonSchema} completes as
     * {@link NexusErrorKind#INVALID_REQUEST} and does not call the model.
     */
    public CompletableFuture<JsonGenerationResult> generateJson(Plugin owner, GenerationRequest request) {
        if (owner == null || request == null) {
            throw new IllegalArgumentException("owner and request are required");
        }
        GenerationRuntime current = runtime;
        if (closed || current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI is not enabled"));
        }
        if (request.promptId().isPresent()) {
            Optional<ApiPromptRegistry.Effective> effective = ApiPromptRegistry.get()
                    .effective(current.catalog(), request.promptId().get());
            if (effective.isEmpty()) {
                return wrap(generate(owner, request, null));
            }
            Object stored = effective.get().schema();
            if (stored instanceof JsonSchema schema) {
                return generateJson(owner, request, schema);
            }
        }
        return missingSchema(owner, request, current);
    }

    private CompletableFuture<GenerationResult> generate(Plugin owner, GenerationRequest request, JsonSession session) {
        if (owner == null || request == null) {
            throw new IllegalArgumentException("owner and request are required");
        }
        GenerationRuntime current = runtime;
        if (closed || current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI is not enabled"));
        }
        CallTrace trace = CallTrace.start(
                RequestOrigin.API,
                owner.getName(),
                request.playerId().orElse(null),
                request.promptId().orElse(""),
                request.label());
        CompletableFuture<GenerationResult> future = new CompletableFuture<>();
        if (session != null) {
            jsonSessions.put(future, session);
        }
        pending.put(future, trace);
        if (!configured(current)) {
            schedule(future, trace, () -> deliver(
                    future, trace, failure(trace, request, null, current, NexusErrors.notConfigured(current.config())), true));
            return future;
        }
        Lookup lookup = lookup(current, request);
        if (lookup.error != null) {
            GenerationError error = NexusErrors.of(current.config(), lookup.error, lookup.message, 0, 0L);
            schedule(future, trace, () -> deliver(future, trace, failure(trace, request, lookup, current, error), true));
            return future;
        }
        if (needsPlayer(request, lookup.named, current)) {
            Player player = GenerationRequestFacts.player(request);
            if (regions.owns(player)) {
                rememberGroups(player);
                PlayerFacts facts = read(player, lookup.named, request);
                schedule(future, trace, () -> pipeline(owner, request, current, trace, future, lookup, facts));
            } else {
                regions.run(plugin, player, () -> {
                    rememberGroups(player);
                    PlayerFacts facts = read(player, lookup.named, request);
                    schedule(future, trace, () -> pipeline(owner, request, current, trace, future, lookup, facts));
                }, () -> schedule(future, trace, () -> deliver(
                        future, trace, failure(trace, request, lookup, current, NexusErrors.of(
                                current.config(), NexusErrorKind.PLAYER_UNAVAILABLE, NexusErrors.PLAYER_UNAVAILABLE, 0, 0L)),
                        true)));
            }
            return future;
        }
        schedule(future, trace, () -> pipeline(owner, request, current, trace, future, lookup, PlayerFacts.none()));
        return future;
    }

    private void pipeline(
            Plugin owner,
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            PlayerFacts facts
    ) {
        if (facts == null || !facts.available()) {
            deliver(future, trace, failure(trace, request, lookup, current, NexusErrors.of(
                    current.config(), NexusErrorKind.PLAYER_UNAVAILABLE, NexusErrors.PLAYER_UNAVAILABLE, 0, 0L)), true);
            return;
        }
        String prompt = PromptAssembly.userPrompt(lookup.template, lookup.named, request, facts);
        String knowledgeText = request.knowledgeQuery().orElse(prompt);
        if (needsContext(request, lookup.named, current)) {
            ContextRequest contextRequest = new ContextRequest(
                    request.playerId().orElse(null),
                    facts.playerName(),
                    facts.worldName(),
                    lookup.named.id(),
                    ContextRequest.Purpose.PLACEHOLDER);
            current.context().collect(contextRequest, lookup.named.context()).whenComplete((block, error) ->
                    schedule(future, trace, () -> afterText(
                            owner, request, current, trace, future, lookup,
                            ContextBlock.appendUser(prompt, block), knowledgeText)));
            return;
        }
        afterText(owner, request, current, trace, future, lookup, prompt, knowledgeText);
    }

    private void afterText(
            Plugin owner,
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            String prompt,
            String knowledgeText
    ) {
        Prepared prepared = prepare(current, request, lookup, prompt, jsonSessions.get(future), knowledgeText);
        if (request.cacheMode() == CacheMode.CACHED) {
            Optional<AiCache.CachedAnswer> hit = current.cache().lookup(prepared.cacheKey);
            JsonSession session = jsonSessions.get(future);
            if (hit.isPresent() && (session == null || JsonDocuments.isObject(hit.get().text()))) {
                AiCache.CachedAnswer cached = hit.get();
                if (session != null) {
                    session.json = cached.text();
                    session.map = JsonDocuments.mapOf(cached.text());
                    session.mode = modeOf(cached.note());
                    session.repaired = false;
                }
                GenerationResult result = GenerationResult.of(
                        trace,
                        true,
                        cached.text(),
                        ResultSource.CACHE,
                        cached.providerId(),
                        cached.model(),
                        false,
                        TokenUsage.none(),
                        "",
                        false,
                        0,
                        Duration.ZERO,
                        null);
                deliver(future, trace, result, false);
                return;
            }
            AiHttpClient.Flight flight = current.http().attach(prepared.cacheKey);
            if (!flight.leader()) {
                Runnable joined = onInFlightJoin;
                if (joined != null) {
                    joined.run();
                }
                flight.future().whenComplete((shared, error) -> schedule(future, trace, () ->
                        deliverJoin(future, trace, request, current, lookup, shared, error)));
                return;
            }
            lead(owner, request, current, trace, future, lookup, prepared, flight.future());
            return;
        }
        lead(owner, request, current, trace, future, lookup, prepared, null);
    }

    private void lead(
            Plugin owner,
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared
    ) {
        String admissionKey = admissionKey(owner, request, lookup);
        Optional<String> rejection = current.gate().tryAdmit(request.playerId().orElse(null), admissionKey, false);
        if (rejection.isPresent()) {
            GenerationError error = NexusErrors.fromGate(current.config(), rejection.get());
            failShared(current, prepared, shared, error);
            deliver(future, trace, failure(trace, request, lookup, current, error), true);
            return;
        }
        QuotaPolicy policy = quotas;
        if (policy != null) {
            long estimate = QuotaEstimates.forCall(current.config(), prepared.prompt, prepared.overrides, null);
            QuotaPolicy.Decision decision = policy.tryReserve(QuotaPolicy.Charge.of(trace, estimate, null));
            if (!decision.allowed()) {
                GenerationError error = NexusErrors.of(
                        current.config(), NexusErrorKind.QUOTA_EXCEEDED, decision.message(), 0, 0L);
                failShared(current, prepared, shared, error);
                deliver(future, trace, failure(trace, request, lookup, current, error), true);
                return;
            }
            policy.track(trace.requestId(), decision.hold());
        }
        long pauseStamp = current.gate().pauseStamp();
        long failureEpoch = current.gate().failureEpoch(admissionKey);
        NexusErrorKind quota;
        try {
            quota = hooks.quotaBlock(trace);
        } catch (Throwable thrown) {
            logFailure(current, thrown);
            quota = NexusErrorKind.PROVIDER_ERROR;
        }
        if (quota != null) {
            GenerationError error = NexusErrors.of(current.config(), quota, messageFor(quota), 0, 0L);
            failShared(current, prepared, shared, error);
            deliver(future, trace, failure(trace, request, lookup, current, error), true);
            return;
        }
        boolean cancel;
        try {
            cancel = hooks.beforeGenerate(trace);
        } catch (Throwable thrown) {
            logFailure(current, thrown);
            GenerationError error = NexusErrors.of(current.config(), NexusErrorKind.PROVIDER_ERROR, "Provider error", 0, 0L);
            failShared(current, prepared, shared, error);
            deliver(future, trace, failure(trace, request, lookup, current, error), true);
            return;
        }
        if (cancel) {
            GenerationError error = NexusErrors.of(current.config(), NexusErrorKind.CANCELLED, NexusErrors.CANCELLED, 0, 0L);
            failShared(current, prepared, shared, error);
            deliver(future, trace, failure(trace, request, lookup, current, error), true);
            return;
        }
        CompletableFuture<ModelAnswer> answer;
        try {
            answer = current.provider().answer(prepared.prompt, prepared.overrides, false, trace);
        } catch (RuntimeException thrown) {
            finishHttp(request, current, trace, future, lookup, prepared, shared, admissionKey, pauseStamp, failureEpoch, null, thrown);
            return;
        }
        answer.whenComplete((value, error) -> runHttpFinish(current, prepared, shared, future, trace, () ->
                finishHttp(request, current, trace, future, lookup, prepared, shared, admissionKey, pauseStamp, failureEpoch, value, error)));
    }

    private void finishHttp(
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            String admissionKey,
            long pauseStamp,
            long failureEpoch,
            ModelAnswer answer,
            Throwable error
    ) {
        try {
            if (PreCancelled.find(error) != null) {
                GenerationError generationError = GenerationEvents.from(error);
                releaseShared(current, prepared, shared, SharedCompletion.fail(PreCancelled.find(error), generationError));
                deliver(future, trace, failure(trace, request, lookup, current, generationError), true);
                return;
            }
            JsonSession session = jsonSessions.get(future);
            if (session != null && finishStructured(
                    request, current, trace, future, lookup, prepared, shared, admissionKey,
                    pauseStamp, failureEpoch, session, answer, error)) {
                return;
            }
            if (error == null) {
                String rejection = PlayerInput.rejectionReason(answer == null ? null : answer.text(), prepared.prompt);
                if (rejection != null) {
                    error = new AiRequestException(AiErrorKind.REJECTED, 0, rejection, null);
                } else if (answer != null && PlayerInput.emptiedByMarkup(answer.text(), current.config().allowMarkup())) {
                    error = new AiRequestException(AiErrorKind.MARKUP_ONLY, 0, PlayerInput.MARKUP_ONLY, null);
                } else if (answer == null || answer.text() == null || answer.text().isBlank()
                        || PlayerInput.stripSectionSigns(answer.text(), current.config().allowMarkup()).isBlank()) {
                    error = new AiRequestException(AiErrorKind.EMPTY_REPLY, 0, PlayerInput.EMPTY_REPLY, null);
                }
            }
            if (error != null) {
                recordHttpFailure(current, admissionKey, error);
                GenerationError generationError = NexusErrors.fromThrowable(current.config(), error);
                releaseShared(current, prepared, shared, SharedCompletion.fail(AiErrors.unwrap(error), generationError));
                deliver(future, trace, failure(trace, request, lookup, current, generationError), true);
                return;
            }
            String text = SecretMask.redact(
                    PlayerInput.stripSectionSigns(answer.text(), current.config().allowMarkup()).trim(),
                    current.config().configuredSecrets());
            EventDispatcher.get().post(
                    trace,
                    text,
                    answer.providerId(),
                    answer.model(),
                    answer.fallbackModelUsed(),
                    TokenUsage.from(answer.usage()),
                    answer.finishReason(),
                    answer.attempts(),
                    null);
            current.gate().recordSuccess(admissionKey, pauseStamp, failureEpoch, true);
            if (prepared.writeCache) {
                writeCache(current, prepared, answer, text, "");
            }
            boolean truncated = LengthCutoff.isLength(answer.finishReason());
            TokenUsage usage = TokenUsage.from(answer.usage());
            releaseShared(current, prepared, shared, SharedCompletion.ok(new ModelAnswer(
                    text,
                    answer.cacheTtl(),
                    answer.providerId(),
                    answer.model(),
                    answer.usage(),
                    answer.finishReason(),
                    answer.attempts(),
                    answer.fallbackModelUsed(),
                    answer.httpNanos())));
            GenerationResult result = GenerationResult.of(
                    trace,
                    true,
                    text,
                    ResultSource.MODEL,
                    answer.providerId(),
                    answer.model(),
                    answer.fallbackModelUsed(),
                    usage,
                    answer.finishReason(),
                    truncated,
                    answer.attempts(),
                    Duration.ofNanos(Math.max(0L, answer.httpNanos())),
                    null);
            deliver(future, trace, result, true);
        } catch (Throwable thrown) {
            logFailure(current, thrown);
            GenerationError generationError = NexusErrors.of(
                    current.config(), NexusErrorKind.PROVIDER_ERROR, AiErrors.detail(thrown), 0, 0L);
            releaseShared(current, prepared, shared, SharedCompletion.fail(thrown, generationError));
            deliver(future, trace, failure(trace, request, lookup, current, generationError), true);
        }
    }

    /**
     * JSON replies skip the text formatter. Returns true when this method delivered a result
     * or scheduled the one repair. A provider error on the first attempt returns false so the
     * normal failure path can run.
     */
    private boolean finishStructured(
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            String admissionKey,
            long pauseStamp,
            long failureEpoch,
            JsonSession session,
            ModelAnswer answer,
            Throwable error
    ) {
        if (error != null && !session.repairUsed) {
            return false;
        }
        if (error != null) {
            int attempts = session.firstAttempts + StructuredOutputSupport.attempts(session.repairOverrides);
            ResponseUsage usage = session.firstUsage;
            AiRequestException typed = AiErrors.find(error);
            if (typed != null && typed.usage() != null) {
                usage = usage.plus(typed.usage());
            }
            session.repaired = true;
            recordHttpFailure(current, admissionKey, error);
            GenerationError generationError = NexusErrors.fromThrowable(current.config(), error);
            releaseShared(current, prepared, shared, SharedCompletion.fail(AiErrors.unwrap(error), generationError, session.errors));
            String provider = answer == null ? "" : answer.providerId();
            String model = answer == null ? "" : answer.model();
            deliver(future, trace, failed(trace, request, lookup, current, generationError, attempts, usage, provider, model), true);
            return true;
        }
        session.mode = StructuredOutputSupport.active(prepared.overrides);
        JsonDocuments.Outcome outcome = JsonDocuments.read(
                answer == null ? null : answer.text(),
                session.schema,
                current.config().allowMarkup(),
                current.config().configuredSecrets(),
                prepared.prompt);
        if (outcome.rejected()) {
            Throwable rejected = new AiRequestException(AiErrorKind.REJECTED, 0, outcome.rejection(), null);
            recordHttpFailure(current, admissionKey, rejected);
            GenerationError generationError = NexusErrors.fromThrowable(current.config(), rejected);
            releaseShared(current, prepared, shared, SharedCompletion.fail(rejected, generationError));
            int attempts = answer == null ? session.firstAttempts : session.firstAttempts + answer.attempts();
            ResponseUsage usage = answer == null ? session.firstUsage : session.firstUsage.plus(answer.usage());
            String provider = answer == null ? "" : answer.providerId();
            String model = answer == null ? "" : answer.model();
            deliver(future, trace, failed(trace, request, lookup, current, generationError, attempts, usage, provider, model), true);
            return true;
        }
        if (!outcome.valid()) {
            session.errors = outcome.errors();
            if (!session.repairUsed && answer != null) {
                session.repairUsed = true;
                session.errors = outcome.errors();
                session.firstUsage = answer.usage();
                session.firstAttempts = answer.attempts();
                boolean length = JsonRepair.lengthLimited(answer.finishReason());
                GenerationOverrides repairOverrides = StructuredOutputSupport.repair(
                        prepared.overrides, answer.text(), outcome.errors(), length);
                if (length) {
                    Integer currentTokens = prepared.overrides.maxTokens(StructuredOutputSupport.DEFAULT_MAX_TOKENS);
                    repairOverrides = repairOverrides.withMaxTokens(JsonRepair.doubledMaxTokens(
                            currentTokens == null ? StructuredOutputSupport.DEFAULT_MAX_TOKENS : currentTokens));
                }
                session.repairOverrides = repairOverrides;
                if (!reserveRepair(current, trace, prepared.prompt, repairOverrides)) {
                    GenerationError quota = NexusErrors.of(
                            current.config(), NexusErrorKind.QUOTA_EXCEEDED, "Quota exceeded", 0, 0L);
                    releaseShared(current, prepared, shared, SharedCompletion.fail(failureFor(quota), quota, session.errors));
                    deliver(future, trace, failed(
                            trace, request, lookup, current, quota, session.firstAttempts, session.firstUsage,
                            answer.providerId(), answer.model()), true);
                    return true;
                }
                if (!(current.provider() instanceof RoutingProvider routing)) {
                    return invalidJson(request, current, trace, future, lookup, prepared, shared, session, answer);
                }
                Prepared repairPrepared = new Prepared(
                        prepared.prompt, repairOverrides, prepared.cacheKey, prepared.ttl, prepared.writeCache);
                routing.repeat(prepared.prompt, repairOverrides, answer.providerId(), answer.model(), trace)
                        .whenComplete((value, repairError) -> runHttpFinish(
                                current, repairPrepared, shared, future, trace, () -> finishHttp(
                                        request, current, trace, future, lookup, repairPrepared, shared,
                                        admissionKey, pauseStamp, failureEpoch, value, repairError)));
                return true;
            }
            return invalidJson(request, current, trace, future, lookup, prepared, shared, session, answer);
        }
        String json = outcome.json();
        session.json = json;
        session.map = outcome.map();
        session.errors = List.of();
        session.repaired = session.repairUsed;
        ResponseUsage usage = session.repairUsed ? session.firstUsage.plus(answer.usage()) : answer.usage();
        int attempts = session.repairUsed ? session.firstAttempts + answer.attempts() : answer.attempts();
        long nanos = answer.httpNanos();
        if (session.repairUsed) {
            nanos = saturatingNanos(nanos, 0L);
        }
        EventDispatcher.get().post(
                trace,
                json,
                answer.providerId(),
                answer.model(),
                answer.fallbackModelUsed(),
                TokenUsage.from(usage),
                answer.finishReason(),
                attempts,
                null);
        current.gate().recordSuccess(admissionKey, pauseStamp, failureEpoch, true);
        if (prepared.writeCache) {
            writeCache(current, prepared, answer, json, session.mode.name());
        }
        boolean truncated = LengthCutoff.isLength(answer.finishReason());
        releaseShared(current, prepared, shared, SharedCompletion.ok(new ModelAnswer(
                json,
                answer.cacheTtl(),
                answer.providerId(),
                answer.model(),
                usage,
                answer.finishReason(),
                attempts,
                answer.fallbackModelUsed(),
                nanos,
                session.mode.name())));
        GenerationResult result = GenerationResult.of(
                trace,
                true,
                json,
                ResultSource.MODEL,
                answer.providerId(),
                answer.model(),
                answer.fallbackModelUsed(),
                TokenUsage.from(usage),
                answer.finishReason(),
                truncated,
                attempts,
                Duration.ofNanos(Math.max(0L, nanos)),
                null);
        deliver(future, trace, result, true);
        return true;
    }

    private boolean invalidJson(
            GenerationRequest request,
            GenerationRuntime current,
            CallTrace trace,
            CompletableFuture<GenerationResult> future,
            Lookup lookup,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            JsonSession session,
            ModelAnswer answer
    ) {
        session.repaired = session.repairUsed;
        if (answer != null && session.repairUsed) {
            session.errors = session.errors.isEmpty() ? List.of("$: JSON could not be parsed") : session.errors;
        }
        GenerationError error = NexusErrors.of(
                current.config(), NexusErrorKind.INVALID_JSON, "The reply was not valid JSON", 0, 0L);
        int attempts = answer == null ? session.firstAttempts : session.firstAttempts + answer.attempts();
        ResponseUsage usage = answer == null ? session.firstUsage : session.firstUsage.plus(answer.usage());
        String provider = answer == null ? "" : answer.providerId();
        String model = answer == null ? "" : answer.model();
        releaseShared(current, prepared, shared, SharedCompletion.fail(
                new AiRequestException(AiErrorKind.OTHER, 0, error.message(), null), error, session.errors));
        deliver(future, trace, failed(trace, request, lookup, current, error, attempts, usage, provider, model), true);
        return true;
    }

    private boolean reserveRepair(
            GenerationRuntime current,
            CallTrace trace,
            String prompt,
            GenerationOverrides overrides
    ) {
        QuotaPolicy policy = quotas;
        if (policy == null || trace == null) {
            return true;
        }
        policy.releaseTracked(trace.requestId());
        long estimate = QuotaEstimates.forCall(current.config(), prompt, overrides, null);
        QuotaPolicy.Decision decision = policy.tryReserve(QuotaPolicy.Charge.of(trace, estimate, null));
        if (!decision.allowed()) {
            return false;
        }
        policy.track(trace.requestId(), decision.hold());
        return true;
    }

    private GenerationResult failed(
            CallTrace trace,
            GenerationRequest request,
            Lookup lookup,
            GenerationRuntime current,
            GenerationError error,
            int attempts,
            ResponseUsage usage,
            String providerId,
            String model
    ) {
        return GenerationResult.of(
                trace,
                false,
                fallbackText(current, request, lookup == null ? null : lookup.named),
                ResultSource.FALLBACK,
                providerId,
                model,
                false,
                TokenUsage.from(usage),
                "",
                false,
                attempts,
                Duration.ZERO,
                error);
    }

    private CompletableFuture<JsonGenerationResult> missingSchema(
            Plugin owner,
            GenerationRequest request,
            GenerationRuntime current
    ) {
        CallTrace trace = CallTrace.start(
                RequestOrigin.API,
                owner.getName(),
                request.playerId().orElse(null),
                request.promptId().orElse(""),
                request.label());
        CompletableFuture<GenerationResult> future = new CompletableFuture<>();
        pending.put(future, trace);
        Lookup lookup = lookup(current, request);
        GenerationError error = lookup.error == null
                ? NexusErrors.of(current.config(), NexusErrorKind.INVALID_REQUEST, "prompt has no JSON schema", 0, 0L)
                : NexusErrors.of(current.config(), lookup.error, lookup.message, 0, 0L);
        schedule(future, trace, () -> deliver(future, trace, failure(trace, request, lookup, current, error), true));
        return wrap(future);
    }

    private static CompletableFuture<JsonGenerationResult> wrap(CompletableFuture<GenerationResult> inner) {
        CompletableFuture<JsonGenerationResult> outer = new CompletableFuture<>();
        inner.whenComplete((result, error) -> {
            if (error != null) {
                outer.completeExceptionally(unwrap(error));
                return;
            }
            outer.complete(JsonGenerationResult.of(result, "", Map.of(), StructuredMode.JSON_SCHEMA, false, List.of()));
        });
        return outer;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? error : current;
    }

    private static StructuredMode modeOf(String name) {
        if (name == null || name.isBlank()) {
            return StructuredMode.JSON_SCHEMA;
        }
        try {
            return StructuredMode.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return StructuredMode.JSON_SCHEMA;
        }
    }

    private static long saturatingNanos(long left, long right) {
        long sum = left + right;
        if (sum < 0L) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private void deliverJoin(
            CompletableFuture<GenerationResult> future,
            CallTrace trace,
            GenerationRequest request,
            GenerationRuntime current,
            Lookup lookup,
            SharedCompletion shared,
            Throwable error
    ) {
        JsonSession session = jsonSessions.get(future);
        if (session != null && (shared == null || error != null || !shared.ok())) {
            if (shared != null) {
                session.errors = shared.notes();
            }
            session.repaired = false;
        }
        if (shared == null || error != null || !shared.ok()) {
            GenerationError generationError;
            if (shared != null && shared.apiError() != null) {
                generationError = shared.apiError();
            } else if (shared != null && shared.failure() != null) {
                generationError = NexusErrors.fromThrowable(current.config(), shared.failure());
            } else if (error != null) {
                generationError = NexusErrors.fromThrowable(current.config(), error);
            } else {
                generationError = NexusErrors.of(current.config(), NexusErrorKind.PROVIDER_ERROR, "Provider error", 0, 0L);
            }
            deliver(future, trace, failure(trace, request, lookup, current, generationError), true, false);
            return;
        }
        ModelAnswer answer = shared.answer();
        String text = SecretMask.redact(
                PlayerInput.stripSectionSigns(answer.text(), current.config().allowMarkup()).trim(),
                current.config().configuredSecrets());
        if (session != null) {
            session.json = text;
            session.map = JsonDocuments.mapOf(text);
            session.mode = modeOf(answer.structuredMode());
            session.repaired = false;
        }
        GenerationResult result = GenerationResult.of(
                trace,
                true,
                text,
                ResultSource.IN_FLIGHT,
                answer.providerId(),
                answer.model(),
                answer.fallbackModelUsed(),
                TokenUsage.none(),
                answer.finishReason(),
                LengthCutoff.isLength(answer.finishReason()),
                0,
                Duration.ZERO,
                null);
        deliver(future, trace, result, true, false);
    }

    private void recordHttpFailure(GenerationRuntime current, String admissionKey, Throwable error) {
        logFailure(current, error);
        if (AiErrors.localMissingKey(error)) {
            return;
        }
        AiErrorKind kind = AiErrors.classify(error);
        if (HttpPool.isQueueFull(error)) {
            return;
        }
        if (kind == AiErrorKind.MARKUP_ONLY) {
            current.gate().recordFailure(admissionKey, kind, 0L, true);
            logger.fine(PlayerInput.MARKUP_ONLY);
            return;
        }
        if (kind == AiErrorKind.LOCAL_LIMIT || kind == AiErrorKind.LOCAL_QUOTA || kind == AiErrorKind.REJECTED) {
            return;
        }
        AiRequestException typed = AiErrors.find(error);
        long retryAfter = typed == null ? 0L : typed.retryAfterSeconds();
        current.gate().recordFailure(
                admissionKey, kind, retryAfter, true, AiRequestException.pausedProvidersOf(error));
        current.diagnostics().report(kind, AiErrors.detail(error), current.gate().isPaused());
    }

    private void writeCache(GenerationRuntime current, Prepared prepared, ModelAnswer answer, String text, String note) {
        Duration ttl = prepared.ttl;
        if (answer.cacheTtl() != null) {
            Duration normal = ttl != null ? ttl : current.config().getCacheTtl();
            if (answer.cacheTtl().compareTo(normal) < 0) {
                ttl = answer.cacheTtl();
            }
        }
        if (ttl != null) {
            current.cache().put(prepared.cacheKey, text, answer.providerId(), answer.model(), ttl, note);
        } else {
            current.cache().put(prepared.cacheKey, text, answer.providerId(), answer.model(), note);
        }
    }

    private void failShared(
            GenerationRuntime current,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            GenerationError error
    ) {
        releaseShared(current, prepared, shared, SharedCompletion.fail(failureFor(error), error));
    }

    private void releaseShared(
            GenerationRuntime current,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            SharedCompletion completion
    ) {
        if (current == null || shared == null || prepared == null || prepared.cacheKey == null) {
            return;
        }
        current.http().completeShared(prepared.cacheKey, shared, completion);
    }

    /**
     * The provider callback must release the shared slot even when this call was already
     * completed by shutdown. {@link #schedule} would return before that release.
     */
    private void runHttpFinish(
            GenerationRuntime current,
            Prepared prepared,
            CompletableFuture<SharedCompletion> shared,
            CompletableFuture<GenerationResult> future,
            CallTrace trace,
            Runnable task
    ) {
        Runnable guarded = () -> {
            if (future.isDone() || closed) {
                releaseQuota(trace);
                releaseShared(current, prepared, shared, SharedCompletion.fail(
                        new AiRequestException(AiErrorKind.OTHER, 0, NexusErrors.SHUTDOWN, null),
                        NexusErrors.of(current.config(), NexusErrorKind.SHUTDOWN, NexusErrors.SHUTDOWN, 0, 0L)));
                return;
            }
            task.run();
        };
        try {
            http.execute(guarded);
        } catch (RejectedExecutionException rejected) {
            if (closed || future.isDone()) {
                releaseShared(current, prepared, shared, SharedCompletion.fail(
                        new AiRequestException(AiErrorKind.OTHER, 0, NexusErrors.SHUTDOWN, null),
                        NexusErrors.of(current.config(), NexusErrorKind.SHUTDOWN, NexusErrors.SHUTDOWN, 0, 0L)));
                return;
            }
            releaseShared(current, prepared, shared, SharedCompletion.fail(
                    new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, HttpPool.QUEUE_FULL, null),
                    NexusErrors.of(current.config(), NexusErrorKind.QUEUE_FULL, HttpPool.QUEUE_FULL, 0, 0L)));
            deliverOffCaller(future, trace, GenerationResult.of(
                    trace,
                    false,
                    runtime == null ? "" : SecretMask.redact(
                            runtime.config().getFallback() == null ? "" : runtime.config().getFallback(),
                            runtime.config().configuredSecrets()),
                    ResultSource.FALLBACK,
                    "",
                    "",
                    false,
                    TokenUsage.none(),
                    "",
                    false,
                    0,
                    Duration.ZERO,
                    NexusErrors.of(runtime == null ? null : runtime.config(), NexusErrorKind.QUEUE_FULL, HttpPool.QUEUE_FULL, 0, 0L)));
        }
    }

    private static Throwable failureFor(GenerationError error) {
        if (error == null) {
            return new AiRequestException(AiErrorKind.OTHER, 0, "Provider error", null);
        }
        AiErrorKind kind = switch (error.kind()) {
            case LOCAL_LIMIT, PAUSED, BACKOFF -> AiErrorKind.LOCAL_LIMIT;
            case QUOTA_EXCEEDED -> AiErrorKind.LOCAL_QUOTA;
            case REJECTED -> AiErrorKind.REJECTED;
            case EMPTY_REPLY -> AiErrorKind.EMPTY_REPLY;
            case MARKUP_ONLY -> AiErrorKind.MARKUP_ONLY;
            case RATE_LIMIT -> AiErrorKind.RATE_LIMIT;
            case PROVIDER_QUOTA -> AiErrorKind.QUOTA;
            case BAD_KEY -> AiErrorKind.BAD_KEY;
            case UNKNOWN_MODEL -> AiErrorKind.UNKNOWN_MODEL;
            case TIMEOUT -> AiErrorKind.TIMEOUT;
            default -> AiErrorKind.OTHER;
        };
        long retryAfter = error.retryAfterSeconds().orElse(0L);
        return new AiRequestException(kind, error.httpStatus(), error.message(), null, retryAfter);
    }

    private Prepared prepare(
            GenerationRuntime current,
            GenerationRequest request,
            Lookup lookup,
            String prompt,
            JsonSession session,
            String knowledgeText
    ) {
        PluginConfig config = current.config();
        GenerationOverrides base = lookup.named == null
                ? ResolvedPrompt.literal(prompt, config).overrides()
                : ResolvedPrompt.named(lookup.named, prompt, config).overrides();
        GenerationOverrides top = GenerationOverrides.none();
        if (GenerationRequestFacts.systemPromptSpecified(request)) {
            top = top.withSystemPrompt(nullToEmpty(GenerationRequestFacts.systemPromptRaw(request)));
        }
        if (GenerationRequestFacts.temperatureSpecified(request)) {
            top = top.withTemperature(GenerationRequestFacts.temperatureRaw(request));
        }
        if (GenerationRequestFacts.maxTokensSpecified(request)) {
            top = top.withMaxTokens(GenerationRequestFacts.maxTokensRaw(request));
        }
        if (request.model().isPresent()) {
            top = top.withModel(request.model().get());
        }
        if (session != null && request.format().isPresent()) {
            logger.fine("Format is ignored for JSON.");
        } else if (request.format().isPresent()) {
            String raw = request.format().get();
            if (!FormatPresets.known(raw)) {
                logger.fine("Unknown format '" + clipFormat(raw) + "'. Using " + config.defaultFormatId() + ".");
            }
            top = top.withFormat(raw);
        }
        String notice = lookup.named != null ? lookup.named.id() : request.label();
        GenerationOverrides merged = base.overlay(top);
        if (notice != null && !notice.isBlank()) {
            merged = merged.withNoticeId(notice);
        }
        List<String> names;
        if (!request.knowledge().isEmpty()) {
            names = request.knowledge();
        } else if (lookup.named != null) {
            names = lookup.named.knowledge();
        } else {
            names = List.of();
        }
        KnowledgeRequest knowledgeRequest = new KnowledgeRequest(
                lookup.named == null
                        ? config.knowledgeSelect()
                        : KnowledgeRequests.effective(lookup.named.knowledgeSelect(), config.knowledgeSelect()),
                knowledgeText,
                lookup.named == null ? List.of() : lookup.named.knowledgeKeywords());
        KnowledgeComposer.Prepared knowledge = KnowledgeComposer.prepare(
                merged, config.getSystemPrompt(), current.knowledge(), names, knowledgeRequest);
        GenerationOverrides overrides = knowledge.overrides();
        String token = PromptAssembly.knowledgeToken(request, knowledge.cacheToken());
        String format = overrides.formatOr(config.defaultFormatId());
        if (session != null && session.schema != null) {
            overrides = StructuredOutputSupport.call(overrides, session.schema);
            if (!overrides.maxTokensSpecified()) {
                overrides = overrides.withMaxTokens(StructuredOutputSupport.DEFAULT_MAX_TOKENS);
            }
            String suffix = "json:" + session.schema.hash();
            token = token == null || token.isBlank() ? suffix : token + suffix;
            format = config.defaultFormatId();
        }
        String model = overrides.model(config.getModel());
        String cacheKey = current.http().cacheKey(model, prompt, format, token);
        Duration ttl = request.ttl().orElse(lookup.named == null ? null : lookup.named.ttl());
        return new Prepared(prompt, overrides, cacheKey, ttl, request.cacheMode() == CacheMode.CACHED);
    }

    /**
     * Spec §4.3: the prompt id, shared with placeholders of that prompt, or
     * {@code api:<owner>:<first 16 hex SHA-256 of the template>}.
     */
    private static String admissionKey(Plugin owner, GenerationRequest request, Lookup lookup) {
        if (lookup.named != null) {
            return lookup.named.id();
        }
        String template = request.template().orElse("");
        return "api:" + owner.getName() + ":" + KnowledgeBase.sha256(template);
    }

    private boolean needsPlayer(GenerationRequest request, NamedPrompt named, GenerationRuntime current) {
        if (GenerationRequestFacts.player(request) == null) {
            return false;
        }
        try {
            if (hooks.needsPlayerForQuotas(request)) {
                return true;
            }
        } catch (Throwable thrown) {
            logFailure(current, thrown);
        }
        QuotaPolicy policy = quotas;
        if (policy != null && policy.groupsConfigured()) {
            return true;
        }
        String template = named == null ? request.template().orElse("") : named.template();
        Set<String> userNames = new HashSet<>();
        if (named != null) {
            userNames.addAll(named.vars().keySet());
        }
        userNames.addAll(request.vars().keySet());
        if (ContextVariables.usesBuiltIn(template, userNames)) {
            return true;
        }
        if (named != null) {
            for (Map.Entry<String, String> entry : named.vars().entrySet()) {
                if (request.vars().containsKey(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue();
                if (value != null && value.indexOf('%') >= 0 && template.contains('{' + entry.getKey() + '}')) {
                    return true;
                }
            }
        }
        return needsContext(request, named, current);
    }

    private static boolean needsContext(GenerationRequest request, NamedPrompt named, GenerationRuntime current) {
        return GenerationRequestFacts.player(request) != null
                && named != null
                && named.context().active()
                && current.context() != null
                && current.config().contextSettings().enabled();
    }

    private PlayerFacts read(Player player, NamedPrompt named, GenerationRequest request) {
        try {
            PlayerFacts facts = reader.read(player, named, request);
            return facts == null ? PlayerFacts.unavailable() : facts;
        } catch (Throwable thrown) {
            logger.log(Level.FINE, "{0}", thrown.toString());
            return PlayerFacts.unavailable();
        }
    }

    private Lookup lookup(GenerationRuntime current, GenerationRequest request) {
        int maxTemplate = current.config().pluginApiMaxTemplateChars();
        int maxVar = current.config().pluginApiMaxVarChars();
        NamedPrompt named = null;
        if (request.promptId().isPresent()) {
            named = ApiPromptRegistry.get().effective(current.catalog(), request.promptId().get())
                    .map(ApiPromptRegistry.Effective::prompt)
                    .orElse(null);
            if (named == null) {
                return Lookup.error(null, NexusErrorKind.UNKNOWN_PROMPT, NexusErrors.UNKNOWN_PROMPT);
            }
        }
        if (request.template().isPresent()) {
            String template = request.template().get();
            if (template.codePointCount(0, template.length()) > maxTemplate) {
                return Lookup.error(
                        null,
                        NexusErrorKind.INVALID_REQUEST,
                        "template is longer than plugin-api.max-template-chars (" + maxTemplate + ")");
            }
        }
        for (Map.Entry<String, String> entry : request.vars().entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (value.codePointCount(0, value.length()) > maxVar) {
                return Lookup.error(
                        named,
                        NexusErrorKind.INVALID_REQUEST,
                        "variable '" + entry.getKey() + "' is longer than plugin-api.max-var-chars (" + maxVar + ")");
            }
        }
        if (named != null) {
            return Lookup.ok(named, named.template());
        }
        return Lookup.ok(null, request.template().orElse(""));
    }

    private static boolean configured(GenerationRuntime current) {
        PluginConfig config = current.config();
        return config.pluginApiEnabled() && !config.requestsHeld() && config.canSendChatRequests();
    }

    private GenerationResult failure(CallTrace trace, GenerationRequest request, Lookup lookup, GenerationRuntime current, GenerationError error) {
        return GenerationResult.of(
                trace,
                false,
                fallbackText(current, request, lookup == null ? null : lookup.named),
                ResultSource.FALLBACK,
                "",
                "",
                false,
                TokenUsage.none(),
                "",
                false,
                0,
                Duration.ZERO,
                error);
    }

    private String fallbackText(GenerationRuntime current, GenerationRequest request, NamedPrompt named) {
        String text;
        if (request != null && request.fallback().isPresent()) {
            text = request.fallback().get();
        } else if (named != null && named.fallback() != null) {
            text = named.fallback();
        } else {
            text = current.config().getFallback();
        }
        if (text == null) {
            text = "";
        }
        return SecretMask.redact(text, current.config().configuredSecrets());
    }

    private GenerationResult shutdownResult(CallTrace trace) {
        GenerationRuntime current = runtime;
        PluginConfig config = current == null ? null : current.config();
        String fallback = config == null || config.getFallback() == null ? "" : config.getFallback();
        if (config != null) {
            fallback = SecretMask.redact(fallback, config.configuredSecrets());
        }
        return GenerationResult.of(
                trace,
                false,
                fallback,
                ResultSource.FALLBACK,
                "",
                "",
                false,
                TokenUsage.none(),
                "",
                false,
                0,
                Duration.ZERO,
                NexusErrors.of(config, NexusErrorKind.SHUTDOWN, NexusErrors.SHUTDOWN, 0, 0L));
    }

    private void schedule(CompletableFuture<GenerationResult> future, CallTrace trace, Runnable task) {
        Runnable guarded = () -> {
            if (future.isDone()) {
                return;
            }
            if (closed) {
                deliver(future, trace, shutdownResult(trace), true);
                return;
            }
            try {
                task.run();
            } catch (Throwable thrown) {
                GenerationRuntime current = runtime;
                logFailure(current, thrown);
                PluginConfig config = current == null ? null : current.config();
                GenerationError error = NexusErrors.of(config, NexusErrorKind.PROVIDER_ERROR, AiErrors.detail(thrown), 0, 0L);
                String fallback = current == null ? "" : SecretMask.redact(
                        current.config().getFallback() == null ? "" : current.config().getFallback(),
                        current.config().configuredSecrets());
                deliver(future, trace, GenerationResult.of(
                        trace, false, fallback, ResultSource.FALLBACK, "", "", false, TokenUsage.none(),
                        "", false, 0, Duration.ZERO, error), true);
            }
        };
        try {
            http.execute(guarded);
        } catch (RejectedExecutionException rejected) {
            GenerationResult result = closed
                    ? shutdownResult(trace)
                    : GenerationResult.of(
                    trace,
                    false,
                    runtime == null ? "" : SecretMask.redact(
                            runtime.config().getFallback() == null ? "" : runtime.config().getFallback(),
                            runtime.config().configuredSecrets()),
                    ResultSource.FALLBACK,
                    "",
                    "",
                    false,
                    TokenUsage.none(),
                    "",
                    false,
                    0,
                    Duration.ZERO,
                    NexusErrors.of(runtime == null ? null : runtime.config(), NexusErrorKind.QUEUE_FULL, HttpPool.QUEUE_FULL, 0, 0L));
            deliverOffCaller(future, trace, result);
        }
    }

    private void deliverOffCaller(CompletableFuture<GenerationResult> future, CallTrace trace, GenerationResult result) {
        Runnable task = () -> deliver(future, trace, result, true);
        try {
            if (scheduler != null) {
                scheduler.execute(task);
                return;
            }
        } catch (RejectedExecutionException ignored) {
            // Last resort: complete on this thread so the future does not stay pending.
        }
        task.run();
    }

    private void deliver(CompletableFuture<GenerationResult> future, CallTrace trace, GenerationResult result, boolean notify) {
        deliver(future, trace, result, notify, true);
    }

    private void deliver(
            CompletableFuture<GenerationResult> future,
            CallTrace trace,
            GenerationResult result,
            boolean notify,
            boolean events
    ) {
        if (events && trace != null && result != null && !result.success()) {
            EventDispatcher.get().fail(trace, result.error().orElse(null), result.attempts(), null);
        }
        releaseQuota(trace);
        if (future != null) {
            jsonSessions.remove(future);
        }
        if (future == null || result == null || pending.remove(future) == null) {
            return;
        }
        // The hook runs before the future completes, so a caller that is already waiting
        // cannot observe the result before after().
        if (notify && result.source() != ResultSource.CACHE) {
            try {
                hooks.after(trace, result);
            } catch (Throwable thrown) {
                logger.log(Level.FINE, "{0}", thrown.toString());
            }
        }
        future.complete(result);
    }

    private void releaseQuota(CallTrace trace) {
        QuotaPolicy policy = quotas;
        if (policy != null && trace != null) {
            policy.releaseTracked(trace.requestId());
        }
    }

    private void rememberGroups(Player player) {
        QuotaPolicy policy = quotas;
        if (policy == null || player == null) {
            return;
        }
        policy.noteName(player.getUniqueId(), player.getName());
        if (!policy.groupsConfigured()) {
            return;
        }
        policy.remember(player.getUniqueId(), QuotaGroups.held(player, policy.groupNames()));
    }

    private void logFailure(GenerationRuntime current, Throwable error) {
        if (error == null) {
            return;
        }
        Iterable<String> secrets = current == null ? List.of() : current.config().configuredSecrets();
        logger.log(Level.FINE, "{0}", LogRedaction.detail(error, secrets));
    }

    private static String messageFor(NexusErrorKind kind) {
        if (kind == NexusErrorKind.CANCELLED) {
            return NexusErrors.CANCELLED;
        }
        if (kind == NexusErrorKind.QUOTA_EXCEEDED) {
            return "Quota exceeded";
        }
        if (kind == NexusErrorKind.NOT_CONFIGURED) {
            return NexusErrors.NOT_CONFIGURED;
        }
        return kind.name();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String clipFormat(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= 64 ? raw : raw.substring(0, 64);
    }

    private static final class JsonSession {
        private final JsonSchema schema;
        private volatile boolean repairUsed;
        private volatile boolean repaired;
        private volatile StructuredMode mode = StructuredMode.JSON_SCHEMA;
        private volatile String json = "";
        private volatile Map<String, Object> map = Map.of();
        private volatile List<String> errors = List.of();
        private volatile ResponseUsage firstUsage = ResponseUsage.none();
        private volatile int firstAttempts;
        private volatile GenerationOverrides repairOverrides = GenerationOverrides.none();

        private JsonSession(JsonSchema schema) {
            this.schema = schema;
        }

        private JsonGenerationResult toResult(GenerationResult meta) {
            return JsonGenerationResult.of(meta, json, map, mode, repaired, errors);
        }
    }

    private static final class Lookup {
        final NamedPrompt named;
        final String template;
        final NexusErrorKind error;
        final String message;

        private Lookup(NamedPrompt named, String template, NexusErrorKind error, String message) {
            this.named = named;
            this.template = template == null ? "" : template;
            this.error = error;
            this.message = message == null ? "" : message;
        }

        static Lookup ok(NamedPrompt named, String template) {
            return new Lookup(named, template, null, "");
        }

        static Lookup error(NamedPrompt named, NexusErrorKind error, String message) {
            return new Lookup(named, "", error, message);
        }
    }

    private static final class Prepared {
        final String prompt;
        final GenerationOverrides overrides;
        final String cacheKey;
        final Duration ttl;
        final boolean writeCache;

        Prepared(String prompt, GenerationOverrides overrides, String cacheKey, Duration ttl, boolean writeCache) {
            this.prompt = prompt;
            this.overrides = overrides;
            this.cacheKey = cacheKey;
            this.ttl = ttl;
            this.writeCache = writeCache;
        }
    }
}
