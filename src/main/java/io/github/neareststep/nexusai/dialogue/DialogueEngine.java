package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.GenerationOverrides;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session, memory, limit, and action policy for {@code /nai talk}.
 * Placeholders never call this class. Actions run only from a native tool call on this path.
 */
public final class DialogueEngine {

    private final MemoryStore memory;
    private final SessionBook sessions;
    private final ActionGate actionGate;
    private final DialogueBudget budget;
    private final GreetingCache greetings;
    private final DialogueModel model;
    private final ActionSink sink;
    private final ActionLog actionLog;
    private final ZoneId zone;

    public DialogueEngine(
            MemoryStore memory,
            SessionBook sessions,
            ActionGate actionGate,
            DialogueBudget budget,
            GreetingCache greetings,
            DialogueModel model,
            ActionSink sink,
            ActionLog actionLog,
            ZoneId zone
    ) {
        this.memory = memory;
        this.sessions = sessions;
        this.actionGate = actionGate;
        this.budget = budget;
        this.greetings = greetings;
        this.model = model;
        this.sink = sink;
        this.actionLog = actionLog == null ? ActionLog.noop() : actionLog;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    public SessionBook sessions() {
        return sessions;
    }

    public TalkResult talk(TalkRequest request) {
        if (request.end()) {
            Optional<SessionBook.Session> closed = sessions.close(request.playerId());
            if (closed.isEmpty()) {
                return TalkResult.of(TalkCode.NO_SESSION, "");
            }
            return TalkResult.of(TalkCode.ENDED, closed.get().characterId());
        }
        if (!request.settings().dialogueEnabled()) {
            return TalkResult.of(TalkCode.DISABLED, request.characterId());
        }
        if (request.characterMissing()) {
            return TalkResult.of(TalkCode.UNKNOWN, request.characterId());
        }
        if (request.message() == null || request.message().isBlank()) {
            return startSession(request);
        }
        return reply(request);
    }

    public List<SessionBook.Session> sweep(long nowMillis) {
        return sessions.expired(nowMillis);
    }

    public Optional<SessionBook.Session> move(UUID player, String world, double x, double y, double z) {
        return sessions.left(player, world, x, y, z);
    }

    private TalkResult startSession(TalkRequest request) {
        if (!budget.tryStartConversation(
                request.playerId(),
                request.nowMillis(),
                request.settings().conversationsPerPlayerPerDay(),
                zone)) {
            return TalkResult.of(TalkCode.DAILY, request.characterId());
        }
        DialogueProfile profile = request.profile();
        DialogueSettings settings = request.settings();
        sessions.open(
                request.playerId(),
                request.characterId(),
                request.world(),
                request.x(),
                request.y(),
                request.z(),
                profile.maxReplies(settings.maxRepliesPerSession()),
                profile.sessionTimeoutSeconds(settings.sessionTimeoutSeconds()),
                profile.leaveRadius(settings.leaveRadius()),
                request.nowMillis()
        );
        String greeting = greeting(request);
        remember(request, "assistant", greeting);
        return TalkResult.text(TalkCode.STARTED, request.characterId(), greeting);
    }

    private TalkResult reply(TalkRequest request) {
        String sanitized = PlayerInput.sanitize(request.message());
        if (sanitized.isBlank()) {
            return TalkResult.of(TalkCode.EMPTY, request.characterId());
        }
        int maxLength = request.settings().maxMessageLength();
        if (DialogueBudget.tooLong(sanitized, maxLength)) {
            return new TalkResult(TalkCode.TOO_LONG, request.characterId(), "", "", maxLength);
        }
        SessionBook.Session session = sessions.get(request.playerId()).orElse(null);
        if (request.sessionChat()) {
            if (session == null) {
                return TalkResult.of(TalkCode.NO_SESSION, request.characterId());
            }
            if (session.timedOut(request.nowMillis())) {
                sessions.close(request.playerId());
                return TalkResult.of(TalkCode.ENDED, session.characterId());
            }
            if (!session.characterId().equals(request.characterId())) {
                return TalkResult.of(TalkCode.NO_SESSION, request.characterId());
            }
            if (session.repliesExhausted()) {
                sessions.close(request.playerId());
                return TalkResult.of(TalkCode.REPLIES, session.characterId());
            }
        }
        int cooldown = request.profile().messageCooldownMillis(request.settings().messageCooldownMillis());
        if (!budget.cooldownReady(request.playerId(), request.nowMillis(), cooldown)) {
            return TalkResult.of(TalkCode.COOLDOWN, request.characterId());
        }
        if (!request.sessionChat() && !budget.tryStartConversation(
                request.playerId(),
                request.nowMillis(),
                request.settings().conversationsPerPlayerPerDay(),
                zone)) {
            return TalkResult.of(TalkCode.DAILY, request.characterId());
        }
        budget.markMessage(request.playerId(), request.nowMillis());
        if (session != null && request.sessionChat()) {
            sessions.touch(request.playerId(), request.nowMillis());
        }

        List<DialogueProtocol.MemoryLine> messages = history(request);
        String wrapped = PlayerInput.wrap(sanitized);
        messages.add(new DialogueProtocol.MemoryLine("user", wrapped));
        trimWindow(messages, request.profile().memoryTurns(request.settings().memoryTurns()), request.settings().memoryMaxChars());
        List<CharacterAction> tools = offeredTools(request);
        ModelReply first;
        try {
            first = model.complete(new ModelCall(
                    request.system(),
                    messages,
                    tools,
                    request.overrides(),
                    request.formatId(),
                    request.playerId(),
                    wrapped
            ));
        } catch (RuntimeException e) {
            return failure(request, e);
        }
        String spoken = first.text();
        if (!tools.isEmpty() && !first.toolsUnsupported() && !first.toolNames().isEmpty()) {
            String note = runTools(request, first.toolNames(), tools);
            List<DialogueProtocol.MemoryLine> follow = new ArrayList<>(messages);
            if (spoken != null && !spoken.isBlank()) {
                follow.add(new DialogueProtocol.MemoryLine("assistant", spoken));
            }
            try {
                ModelReply second = model.complete(new ModelCall(
                        request.system() + "\n\n" + note,
                        follow,
                        List.of(),
                        request.overrides(),
                        request.formatId(),
                        request.playerId(),
                        wrapped
                ));
                spoken = second.text();
            } catch (RuntimeException e) {
                return failure(request, e);
            }
        }
        if (spoken == null || spoken.isBlank()) {
            spoken = request.fallback();
        }
        remember(request, "user", sanitized);
        remember(request, "assistant", spoken);
        if (request.sessionChat()) {
            sessions.addReply(request.playerId());
            SessionBook.Session updated = sessions.get(request.playerId()).orElse(null);
            if (updated != null && updated.repliesExhausted()) {
                sessions.close(request.playerId());
                return new TalkResult(TalkCode.REPLIES, request.characterId(), spoken, "", 0);
            }
        }
        return TalkResult.text(TalkCode.REPLY, request.characterId(), spoken);
    }

    private TalkResult failure(TalkRequest request, RuntimeException error) {
        AiRequestException typed = io.github.neareststep.nexusai.ai.AiErrors.find(error);
        if (typed != null && typed.kind() == io.github.neareststep.nexusai.ai.AiErrorKind.LOCAL_LIMIT) {
            return TalkResult.of(TalkCode.BUSY, request.characterId());
        }
        if (typed != null && (typed.kind() == io.github.neareststep.nexusai.ai.AiErrorKind.REJECTED
                || typed.kind() == io.github.neareststep.nexusai.ai.AiErrorKind.EMPTY_REPLY)) {
            return TalkResult.text(TalkCode.REPLY, request.characterId(), request.fallback());
        }
        String detail = typed != null && typed.getMessage() != null ? typed.getMessage() : error.getMessage();
        return TalkResult.failed(request.characterId(), detail);
    }

    private String greeting(TalkRequest request) {
        if (request.profile().greeting() != null) {
            return request.profile().greeting();
        }
        String cacheKey = GreetingCache.key(request.characterId(), request.system());
        if (request.settings().cacheGreeting()) {
            String cached = greetings.get(cacheKey, request.nowMillis());
            if (cached != null) {
                return cached;
            }
        }
        ModelReply reply;
        try {
            reply = model.complete(new ModelCall(
                    request.system(),
                    List.of(new DialogueProtocol.MemoryLine("user", "Greet the player briefly in character.")),
                    List.of(),
                    request.overrides(),
                    request.formatId(),
                    request.playerId(),
                    ""
            ));
        } catch (RuntimeException e) {
            return request.fallback();
        }
        String text = reply.text() == null || reply.text().isBlank() ? request.fallback() : reply.text();
        if (request.settings().cacheGreeting()) {
            greetings.put(cacheKey, text, request.nowMillis(), request.settings().greetingCacheSeconds() * 1000L);
        }
        return text;
    }

    private List<CharacterAction> offeredTools(TalkRequest request) {
        if (!request.settings().actionsEnabled() || request.actions().isEmpty()) {
            return List.of();
        }
        return request.actions();
    }

    private String runTools(TalkRequest request, List<String> names, List<CharacterAction> defined) {
        StringBuilder note = new StringBuilder(
                "Server action results. Tell the player in character. Do not invent actions or claim one ran when it was refused.");
        int ran = 0;
        int max = request.settings().maxActionsPerReply();
        for (String name : names) {
            CharacterAction action = find(defined, name);
            if (action == null) {
                note.append("\n- ").append(name).append(": refused: unknown action");
                actionLog.record(request.playerName(), request.characterId(), name, "refused: unknown action");
                continue;
            }
            if (ran >= max) {
                note.append("\n- ").append(name).append(": refused: too many actions");
                actionLog.record(request.playerName(), request.characterId(), name, "refused: too many actions");
                continue;
            }
            boolean permitted = action.permission() == null || request.permissions().test(action.permission());
            ActionGate.Decision decision = actionGate.admit(
                    action, request.characterId(), request.playerId(), permitted, request.nowMillis(), zone);
            if (!decision.allowed()) {
                String result = "refused: " + decision.reason();
                note.append("\n- ").append(name).append(": ").append(result);
                actionLog.record(request.playerName(), request.characterId(), name, result);
                continue;
            }
            String command = ActionCommands.render(action.command(), request.playerName(), request.playerId());
            if (command == null) {
                String result = "refused: unsafe player name";
                note.append("\n- ").append(name).append(": ").append(result);
                actionLog.record(request.playerName(), request.characterId(), name, result);
                continue;
            }
            String result;
            try {
                result = sink.run(request.playerId(), action, command);
            } catch (RuntimeException e) {
                result = "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (result == null || result.isBlank()) {
                result = "ran";
            }
            if (result.startsWith("ran") || "ok".equals(result)) {
                actionGate.record(action, request.characterId(), request.playerId(), request.nowMillis(), zone);
                ran++;
            }
            note.append("\n- ").append(name).append(": ").append(result);
            actionLog.record(request.playerName(), request.characterId(), name, result);
        }
        return note.toString();
    }

    private static CharacterAction find(List<CharacterAction> actions, String name) {
        for (CharacterAction action : actions) {
            if (action.name().equals(name)) {
                return action;
            }
        }
        return null;
    }

    private static void trimWindow(List<DialogueProtocol.MemoryLine> messages, int turns, int maxChars) {
        int users = 0;
        for (DialogueProtocol.MemoryLine line : messages) {
            if ("user".equals(line.role())) {
                users++;
            }
        }
        while (users > Math.max(1, turns) && !messages.isEmpty()) {
            DialogueProtocol.MemoryLine removed = messages.remove(0);
            if ("user".equals(removed.role())) {
                users--;
            }
        }
        int cap = Math.max(1, maxChars);
        while (chars(messages) > cap && messages.size() > 1) {
            messages.remove(0);
        }
    }

    private static int chars(List<DialogueProtocol.MemoryLine> messages) {
        int total = 0;
        for (DialogueProtocol.MemoryLine line : messages) {
            total += line.text() == null ? 0 : line.text().length();
        }
        return total;
    }

    private List<DialogueProtocol.MemoryLine> history(TalkRequest request) {
        DialogueSettings settings = request.settings();
        List<TurnMemory.Line> stored = memory.transcript(
                request.playerId(),
                request.characterId(),
                request.nowMillis(),
                request.profile().memoryTurns(settings.memoryTurns()),
                settings.memoryMaxChars(),
                settings.memoryExpiryMillis()
        );
        List<DialogueProtocol.MemoryLine> messages = new ArrayList<>();
        for (TurnMemory.Line line : stored) {
            String text = "user".equals(line.role()) ? PlayerInput.wrap(line.text()) : line.text();
            messages.add(new DialogueProtocol.MemoryLine(line.role(), text));
        }
        return messages;
    }

    private void remember(TalkRequest request, String role, String text) {
        if (text == null) {
            return;
        }
        DialogueSettings settings = request.settings();
        memory.append(
                request.playerId(),
                request.characterId(),
                role,
                text,
                request.nowMillis(),
                request.profile().memoryTurns(settings.memoryTurns()),
                settings.memoryMaxChars(),
                settings.memoryExpiryMillis()
        );
    }

    public interface DialogueModel {
        ModelReply complete(ModelCall call);
    }

    public interface ActionSink {
        String run(UUID playerId, CharacterAction action, String command);
    }

    public record ModelCall(
            String system,
            List<DialogueProtocol.MemoryLine> messages,
            List<CharacterAction> tools,
            GenerationOverrides overrides,
            String formatId,
            UUID playerId,
            String wrappedUser
    ) {
    }

    public record ModelReply(String text, List<String> toolNames, boolean toolsUnsupported) {
        public ModelReply {
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }

        public static ModelReply text(String text) {
            return new ModelReply(text, List.of(), false);
        }
    }

    public record TalkRequest(
            UUID playerId,
            String playerName,
            String characterId,
            String message,
            boolean sessionChat,
            boolean end,
            boolean characterMissing,
            String system,
            String fallback,
            DialogueProfile profile,
            List<CharacterAction> actions,
            DialogueSettings settings,
            GenerationOverrides overrides,
            String formatId,
            String world,
            double x,
            double y,
            double z,
            java.util.function.Predicate<String> permissions,
            long nowMillis
    ) {
        public TalkRequest {
            profile = profile == null ? DialogueProfile.absent() : profile;
            actions = actions == null ? List.of() : List.copyOf(actions);
            settings = settings == null ? DialogueSettings.defaults() : settings;
            overrides = overrides == null ? GenerationOverrides.none() : overrides;
            permissions = permissions == null ? ignored -> false : permissions;
            playerName = playerName == null ? "" : playerName;
            characterId = characterId == null ? "" : characterId;
            fallback = fallback == null ? "..." : fallback;
            system = system == null ? "" : system;
        }
    }
}
