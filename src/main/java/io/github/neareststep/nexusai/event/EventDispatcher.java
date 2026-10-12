package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.ai.ResponseUsage;
import io.github.neareststep.nexusai.api.GenerationError;
import io.github.neareststep.nexusai.api.NexusErrorKind;
import io.github.neareststep.nexusai.api.RequestOrigin;
import io.github.neareststep.nexusai.api.TokenUsage;
import io.github.neareststep.nexusai.api.event.NexusActionEvent;
import io.github.neareststep.nexusai.api.event.NexusGenerateFailEvent;
import io.github.neareststep.nexusai.api.event.NexusModerationFlagEvent;
import io.github.neareststep.nexusai.api.event.NexusPostGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusPreGenerateEvent;
import io.github.neareststep.nexusai.api.event.NexusProviderErrorEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates a Bukkit event only when that event's handler list has listeners, then times
 * {@code callEvent}. A call slower than 50 ms logs one warning per event class per five minutes,
 * naming the listener plugins.
 * <p>
 * With no listeners the cost is the handler-list check. The request is unchanged.
 */
public final class EventDispatcher {

    static final long SLOW_NANOS = 50_000_000L;
    static final long WARN_GAP_NANOS = 5L * 60L * 1_000_000_000L;

    private static final EventDispatcher NONE = new EventDispatcher(
            Logger.getLogger("NexusAI"),
            java.util.List::of,
            event -> { },
            System::nanoTime,
            () -> false);

    private static volatile EventDispatcher current = NONE;

    private final Logger logger;
    private final Consumer<Event> caller;
    private final LongSupplier nanoTime;
    private final BooleanSupplier serverStopping;
    private final ConcurrentHashMap<Class<?>, Long> warnedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Open> opens = new ConcurrentHashMap<>();

    private EventDispatcher(
            Logger logger,
            Supplier<Iterable<String>> secrets,
            Consumer<Event> caller,
            LongSupplier nanoTime,
            BooleanSupplier serverStopping
    ) {
        this.logger = logger == null ? Logger.getLogger("NexusAI") : logger;
        EventTexts.secrets(secrets);
        installCancelMask();
        this.caller = caller == null ? event -> { } : caller;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.serverStopping = serverStopping == null ? () -> false : serverStopping;
    }

    public static EventDispatcher get() {
        return current;
    }

    /** Production dispatcher. Bukkit delivers the event and catches listener failures. */
    public static EventDispatcher bukkit(Logger logger, Supplier<Iterable<String>> secrets) {
        return new EventDispatcher(logger, secrets, EventDispatcher::bukkitCall, System::nanoTime, EventDispatcher::serverStopping);
    }

    /**
     * Tests pass the clock and the caller. {@code serverStopping} suppresses {@code SHUTDOWN} failures.
     */
    public static EventDispatcher create(
            Logger logger,
            Supplier<Iterable<String>> secrets,
            Consumer<Event> caller,
            LongSupplier nanoTime,
            BooleanSupplier serverStopping
    ) {
        return new EventDispatcher(logger, secrets, caller, nanoTime, serverStopping);
    }

    public static void install(EventDispatcher dispatcher) {
        current = dispatcher == null ? NONE : dispatcher;
    }

    /**
     * Before the first HTTP attempt. A second call with the same request id does not fire again
     * (the follow-up model call after character actions).
     *
     * @return cancelled when the listener cancelled the call
     */
    public PreOutcome pre(CallTrace trace, String providerId, String model, int estimatedPromptTokens) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return PreOutcome.GO;
        }
        if (!listening(NexusPreGenerateEvent.class)) {
            return PreOutcome.GO;
        }
        Open created = Open.start(trace);
        Open existing = opens.putIfAbsent(trace.requestId(), created);
        if (existing != null) {
            return PreOutcome.GO;
        }
        NexusPreGenerateEvent event = new NexusPreGenerateEvent(
                trace.requestId(),
                trace.origin(),
                trace.consumer(),
                trace.playerId(),
                EventTexts.plain(trace.promptId()),
                EventTexts.plain(trace.label()),
                EventTexts.plain(providerId),
                EventTexts.plain(model),
                estimatedPromptTokens);
        dispatch(event);
        if (!event.isCancelled()) {
            return PreOutcome.GO;
        }
        String reason = event.cancelReason();
        if (reason == null || reason.isBlank()) {
            reason = "Cancelled";
        }
        return PreOutcome.cancel(EventTexts.message(reason));
    }

    public void providerError(CallTrace trace, String providerId, String model, AiRequestException error, boolean willRetry) {
        if (trace == null || error == null) {
            return;
        }
        NexusErrorKind kind = GenerationEvents.providerKind(error.kind());
        if (kind == null) {
            return;
        }
        boolean want = listening(NexusProviderErrorEvent.class)
                || listening(NexusPostGenerateEvent.class)
                || listening(NexusGenerateFailEvent.class);
        if (!want) {
            return;
        }
        noteAttempt(trace, providerId, model, error.usage(), "", false);
        if (!listening(NexusProviderErrorEvent.class)) {
            return;
        }
        dispatch(new NexusProviderErrorEvent(
                trace.requestId(),
                trace.origin(),
                trace.consumer(),
                trace.playerId(),
                EventTexts.plain(trace.promptId()),
                EventTexts.plain(trace.label()),
                EventTexts.plain(providerId),
                EventTexts.plain(model),
                kind,
                error.status(),
                EventTexts.message(error.getMessage()),
                willRetry));
    }

    /** Records a successful HTTP attempt so a later Post or Fail can report attempts and usage. */
    public void noteExchange(
            CallTrace trace,
            String providerId,
            String model,
            ResponseUsage usage,
            String finishReason,
            boolean fallbackModelUsed
    ) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        if (!listening(NexusPostGenerateEvent.class) && !listening(NexusGenerateFailEvent.class)) {
            return;
        }
        noteAttempt(trace, providerId, model, usage, finishReason, fallbackModelUsed);
    }

    public void post(
            CallTrace trace,
            String text,
            String providerId,
            String model,
            boolean fallbackModelUsed,
            TokenUsage usage,
            String finishReason,
            int attempts,
            Duration latency
    ) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        opens.remove(trace.requestId());
        if (!listening(NexusPostGenerateEvent.class)) {
            return;
        }
        dispatch(new NexusPostGenerateEvent(
                trace.requestId(),
                trace.origin(),
                trace.consumer(),
                trace.playerId(),
                EventTexts.plain(trace.promptId()),
                EventTexts.plain(trace.label()),
                EventTexts.plain(text),
                EventTexts.plain(providerId),
                EventTexts.plain(model),
                fallbackModelUsed,
                usage == null ? TokenUsage.none() : usage,
                EventTexts.plain(finishReason),
                latency == null ? GenerationEvents.latency(trace) : latency,
                attempts));
    }

    /** Post for dialogue and summaries, using facts recorded on the HTTP attempts. */
    public void postFromOpen(CallTrace trace, String text) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        Open open = opens.remove(trace.requestId());
        if (!listening(NexusPostGenerateEvent.class)) {
            return;
        }
        Open facts = open == null ? Open.start(trace) : open;
        dispatch(new NexusPostGenerateEvent(
                trace.requestId(),
                trace.origin(),
                trace.consumer(),
                trace.playerId(),
                EventTexts.plain(trace.promptId()),
                EventTexts.plain(trace.label()),
                EventTexts.plain(text),
                facts.providerId,
                facts.model,
                facts.fallback,
                facts.usage,
                facts.finishReason,
                GenerationEvents.latency(trace),
                facts.attempts));
    }

    public void fail(CallTrace trace, GenerationError error, int attempts, Duration latency) {
        if (trace == null || trace.origin() == RequestOrigin.MODERATION) {
            return;
        }
        Open open = opens.remove(trace.requestId());
        if (error != null && error.kind() == NexusErrorKind.SHUTDOWN && stopping()) {
            return;
        }
        if (!listening(NexusGenerateFailEvent.class)) {
            return;
        }
        int reported = attempts;
        if (reported <= 0 && open != null) {
            reported = open.attempts;
        }
        dispatch(new NexusGenerateFailEvent(
                trace.requestId(),
                trace.origin(),
                trace.consumer(),
                trace.playerId(),
                EventTexts.plain(trace.promptId()),
                EventTexts.plain(trace.label()),
                error,
                reported,
                latency == null ? GenerationEvents.latency(trace) : latency));
    }

    /**
     * On the command thread, before the command runs.
     *
     * @return {@code true} when the listener cancelled the action
     */
    public boolean action(Player player, String characterId, String actionName, String command, boolean console, long requestId) {
        if (!listening(NexusActionEvent.class)) {
            return false;
        }
        NexusActionEvent event = new NexusActionEvent(
                player,
                EventTexts.plain(characterId),
                EventTexts.plain(actionName),
                EventTexts.plain(command),
                console,
                requestId);
        dispatch(event);
        return event.isCancelled();
    }

    public void moderationFlag(java.util.UUID playerId, String playerName, String message, String category, String reason) {
        if (!listening(NexusModerationFlagEvent.class)) {
            return;
        }
        dispatch(new NexusModerationFlagEvent(
                playerId,
                EventTexts.plain(playerName),
                EventTexts.plain(message),
                EventTexts.plain(category),
                EventTexts.reason(reason)));
    }

    public boolean opened(long requestId) {
        return opens.containsKey(requestId);
    }

    public int attempts(long requestId) {
        Open open = opens.get(requestId);
        return open == null ? 0 : open.attempts;
    }

    /** Test hook. Walks the handler list the way Bukkit does, and does not fail the caller. */
    public static void callRegistered(Event event) {
        if (event == null) {
            return;
        }
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
            try {
                listener.callEvent(event);
            } catch (Throwable ignored) {
                // Bukkit logs SEVERE with the plugin name and continues.
            }
        }
    }

    private void noteAttempt(
            CallTrace trace,
            String providerId,
            String model,
            ResponseUsage usage,
            String finishReason,
            boolean fallback
    ) {
        opens.compute(trace.requestId(), (id, open) -> {
            Open row = open == null ? Open.start(trace) : open;
            row.attempts++;
            if (providerId != null && !providerId.isBlank()) {
                row.providerId = EventTexts.plain(providerId);
            }
            if (model != null && !model.isBlank()) {
                row.model = EventTexts.plain(model);
            }
            if (usage != null && (usage.reported() || usage.estimated() || usage.totalTokens() > 0)) {
                row.usage = TokenUsage.from(usage);
            }
            if (finishReason != null && !finishReason.isBlank()) {
                row.finishReason = EventTexts.plain(finishReason);
            }
            if (fallback) {
                row.fallback = true;
            }
            return row;
        });
    }

    private void dispatch(Event event) {
        long started = nanoTime.getAsLong();
        try {
            caller.accept(event);
        } catch (Throwable thrown) {
            logger.log(Level.SEVERE, "Could not deliver " + event.getEventName(), thrown);
        } finally {
            long took = nanoTime.getAsLong() - started;
            if (took > SLOW_NANOS) {
                warnSlow(event, took);
            }
        }
    }

    private void warnSlow(Event event, long tookNanos) {
        Class<?> type = event.getClass();
        long now = nanoTime.getAsLong();
        long[] suppressed = {0L};
        warnedAt.compute(type, (key, previous) -> {
            if (previous != null && now - previous < WARN_GAP_NANOS) {
                suppressed[0] = 1L;
                return previous;
            }
            return now;
        });
        if (suppressed[0] != 0L) {
            return;
        }
        long millis = Math.max(1L, tookNanos / 1_000_000L);
        logger.warning("NexusAI event " + type.getSimpleName()
                + " took " + millis + " ms. Listeners: " + listenerNames(event));
    }

    private static String listenerNames(Event event) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
                if (listener.getPlugin() == null || listener.getPlugin().getName() == null) {
                    continue;
                }
                String name = listener.getPlugin().getName();
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (Throwable ignored) {
            return "";
        }
        return String.join(", ", names);
    }

    static boolean listening(Class<? extends Event> type) {
        try {
            HandlerList list = handlerList(type);
            return list != null && list.getRegisteredListeners().length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static HandlerList handlerList(Class<? extends Event> type) throws ReflectiveOperationException {
        return (HandlerList) type.getMethod("getHandlerList").invoke(null);
    }

    private boolean stopping() {
        try {
            return serverStopping.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void bukkitCall(Event event) {
        try {
            if (Bukkit.getServer() == null) {
                return;
            }
            PluginManager manager = Bukkit.getPluginManager();
            if (manager == null) {
                return;
            }
            manager.callEvent(event);
        } catch (Throwable ignored) {
            // The server is stopping, or Bukkit is not standing. The request still finishes.
        }
    }

    private static void installCancelMask() {
        try {
            var method = NexusPreGenerateEvent.class.getDeclaredMethod("masker", UnaryOperator.class);
            method.setAccessible(true);
            method.invoke(null, (UnaryOperator<String>) EventTexts::message);
        } catch (ReflectiveOperationException ignored) {
            // The failure event still masks the reason when it copies it.
        }
    }

    private static boolean serverStopping() {
        try {
            Object server = Bukkit.getServer();
            if (server == null) {
                return false;
            }
            Object value = server.getClass().getMethod("isStopping").invoke(server);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Whether the listener cancelled the pre-generate event. */
    public record PreOutcome(boolean cancelled, String reason) {
        public static final PreOutcome GO = new PreOutcome(false, "");

        public static PreOutcome cancel(String reason) {
            String text = reason == null || reason.isBlank() ? "Cancelled" : reason;
            return new PreOutcome(true, text);
        }
    }

    private static final class Open {
        private int attempts;
        private String providerId = "";
        private String model = "";
        private TokenUsage usage = TokenUsage.none();
        private String finishReason = "";
        private boolean fallback;

        private static Open start(CallTrace trace) {
            Open open = new Open();
            if (trace != null) {
                open.providerId = "";
                open.model = "";
            }
            return open;
        }
    }
}
