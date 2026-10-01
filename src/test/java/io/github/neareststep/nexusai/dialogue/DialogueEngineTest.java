package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueEngineTest {

    private final UUID player = UUID.randomUUID();
    private final List<String> commands = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<List<CharacterAction>> toolOffers = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void lengthTrimNoticeUsesThePersonaNotTheRenderedSheet() {
        List<String> ids = new ArrayList<>();
        DialogueEngine engine = engine(reply -> {
            ids.add(reply.overrides().noticeId());
            return DialogueEngine.ModelReply.text("Hello.");
        });
        engine.talk(request("hi", false, List.of(), settings(), 1_000L, node -> true));
        assertEquals(List.of("blacksmith"), ids);
        assertFalse(ids.getFirst().contains("Steve"));
        assertFalse(ids.getFirst().contains("Bram"));

        ids.clear();
        DialogueEngine blank = engine(reply -> {
            ids.add(reply.overrides().noticeId());
            return DialogueEngine.ModelReply.text("Hello.");
        });
        blank.talk(new DialogueEngine.TalkRequest(
                player, "Steve", "  ", "hi", false, false, false,
                "You are Bram. The player is Steve.", "...", DialogueProfile.absent(), List.of(),
                settings(), GenerationOverrides.none(), "chat", "world", 0, 64, 0, node -> true, 2_000L));
        assertEquals(List.of("nai talk"), ids);
    }

    @Test
    void plainTextNeverRunsAnActionAndAToolCallUsesTheFixedCommand() {
        CharacterAction action = action(0, 0, null);
        DialogueEngine engine = engine(reply -> {
            toolOffers.add(reply.tools());
            String last = reply.messages().isEmpty() ? "" : reply.messages().get(reply.messages().size() - 1).text();
            if (last.contains("please") && !reply.tools().isEmpty()) {
                return new DialogueEngine.ModelReply("ok", List.of("give_iron"), false);
            }
            if (reply.system().contains("Server action results")) {
                notes.add(reply.system());
            }
            return DialogueEngine.ModelReply.text("Here you go.");
        });
        TalkResult named = engine.talk(request("give_iron", false, List.of(action), settings(), 1_000L, node -> true));
        assertEquals(TalkCode.REPLY, named.code());
        assertEquals("Here you go.", named.text());
        assertTrue(commands.isEmpty());
        TalkResult called = engine.talk(request("please", false, List.of(action), settings(), 10_000L, node -> true));
        assertEquals("give Steve iron_ingot 1", commands.get(0));
        assertTrue(notes.get(0).contains("give_iron: ran"));
        assertEquals("Here you go.", called.text());
    }

    @Test
    void refusedActionsAreToldToTheCharacterAndDoNotRun() {
        DialogueEngine engine = engine(reply -> {
            toolOffers.add(reply.tools());
            if (!reply.tools().isEmpty()) {
                return new DialogueEngine.ModelReply("", List.of("give_iron"), false);
            }
            notes.add(reply.system());
            return DialogueEngine.ModelReply.text("I cannot.");
        });
        DialogueSettings settings = settings();
        engine.talk(request("please", false, List.of(action(3600, 1, "nexusai.action.give_iron")), settings, 1_000L, node -> false));
        assertTrue(commands.isEmpty());
        assertTrue(notes.get(0).contains("refused: permission"));

        notes.clear();
        engine.talk(request("again", false, List.of(action(0, 1, null)), settings, 5_000L, node -> true));
        assertEquals(1, commands.size());
        notes.clear();
        commands.clear();
        engine.talk(request("third", false, List.of(action(0, 1, null)), settings, 9_000L, node -> true));
        assertTrue(commands.isEmpty());
        assertTrue(notes.get(0).contains("refused: daily limit"));
    }

    @Test
    void cooldownBlocksTheSecondRun() {
        DialogueEngine engine = engine(reply -> {
            if (!reply.tools().isEmpty()) {
                return new DialogueEngine.ModelReply("", List.of("give_iron"), false);
            }
            notes.add(reply.system());
            return DialogueEngine.ModelReply.text("done");
        });
        CharacterAction action = action(30, 0, null);
        engine.talk(request("one", false, List.of(action), settings(), 1_000L, node -> true));
        notes.clear();
        commands.clear();
        engine.talk(request("two", false, List.of(action), settings(), 2_000L, node -> true));
        assertTrue(commands.isEmpty());
        assertTrue(notes.get(0).contains("refused: cooldown"));
    }

    @Test
    void unsupportedToolsDoNotParseTheReply() {
        DialogueEngine engine = engine(reply -> {
            toolOffers.add(reply.tools());
            return new DialogueEngine.ModelReply("give_iron", List.of("give_iron"), true);
        });
        engine.talk(request("please", false, List.of(action(0, 0, null)), settings(), 1_000L, node -> true));
        assertTrue(commands.isEmpty());
        assertEquals(1, calls(toolOffers));
    }

    @Test
    void sessionLimitsMemoryAndGreetingStayOffThePoolPath() {
        List<Integer> userLines = new ArrayList<>();
        DialogueEngine engine = engine(reply -> {
            toolOffers.add(reply.tools());
            int users = 0;
            for (DialogueProtocol.MemoryLine line : reply.messages()) {
                if ("user".equals(line.role())) {
                    users++;
                    assertTrue(line.text().contains(PlayerInput.OPEN));
                }
            }
            userLines.add(users);
            return DialogueEngine.ModelReply.text("line");
        });
        DialogueSettings settings = new DialogueSettings(
                true, 8, false, 8000, 0, 30, 8, 2, 0, 0, 20, true, 300, true, false, 1);
        TalkResult started = engine.talk(request("", false, List.of(action(0, 0, null)), settings, 1_000L, node -> true));
        assertEquals(TalkCode.STARTED, started.code());
        assertEquals("Hello.", started.text());
        assertTrue(toolOffers.isEmpty());

        engine.talk(request("one", true, List.of(), settings, 2_000L, node -> true));
        TalkResult second = engine.talk(request("two", true, List.of(), settings, 3_000L, node -> true));
        assertEquals(TalkCode.REPLIES, second.code());
        assertFalse(engine.sessions().has(player));
        assertEquals(TalkCode.NO_SESSION, engine.talk(request("three", true, List.of(), settings, 4_000L, node -> true)).code());

        DialogueEngine memoryEngine = engine(reply -> {
            int users = 0;
            for (DialogueProtocol.MemoryLine line : reply.messages()) {
                if ("user".equals(line.role())) {
                    users++;
                }
            }
            userLines.add(users);
            return DialogueEngine.ModelReply.text("ok");
        });
        DialogueSettings wide = new DialogueSettings(
                true, 8, false, 8000, 0, 0, 0, 30, 0, 0, 200, false, 300, false, false, 1);
        for (int i = 0; i < 10; i++) {
            memoryEngine.talk(request("m" + i, false, List.of(), wide, 10_000L + i * 10L, node -> true));
        }
        assertEquals(8, userLines.get(userLines.size() - 1));
    }

    @Test
    void messageCooldownAndDailyConversationCap() {
        DialogueEngine engine = engine(reply -> DialogueEngine.ModelReply.text("ok"));
        DialogueSettings settings = new DialogueSettings(
                true, 8, false, 8000, 0, 0, 0, 12, 3_000, 1, 4, false, 300, false, false, 1);
        assertEquals(TalkCode.REPLY, engine.talk(request("hi", false, List.of(), settings, 1_000L, node -> true)).code());
        assertEquals(TalkCode.COOLDOWN, engine.talk(request("hi", false, List.of(), settings, 2_000L, node -> true)).code());
        assertEquals(TalkCode.DAILY, engine.talk(request("hi", false, List.of(), settings, 5_000L, node -> true)).code());
        assertEquals(TalkCode.TOO_LONG, engine.talk(request("hello", false, List.of(), settings, 9_000L, node -> true)).code());
    }

    private static int calls(List<List<CharacterAction>> offers) {
        return offers.size();
    }

    private DialogueEngine engine(DialogueEngine.DialogueModel model) {
        return new DialogueEngine(
                new MemoryStore(),
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                call -> {
                    calls.incrementAndGet();
                    return model.complete(call);
                },
                (id, action, command) -> {
                    commands.add(command);
                    return "ran";
                },
                ActionLog.noop(),
                ZoneId.of("UTC")
        );
    }

    private DialogueEngine.TalkRequest request(
            String message,
            boolean sessionChat,
            List<CharacterAction> actions,
            DialogueSettings settings,
            long now,
            java.util.function.Predicate<String> permissions
    ) {
        DialogueProfile profile = new DialogueProfile(true, message.isEmpty() ? "Hello." : null, null, null, null, null, null);
        if (!message.isEmpty()) {
            profile = DialogueProfile.absent();
        }
        return new DialogueEngine.TalkRequest(
                player,
                "Steve",
                "blacksmith",
                message,
                sessionChat,
                false,
                false,
                "You are Bram.",
                "...",
                profile,
                actions,
                settings,
                GenerationOverrides.none(),
                "chat",
                "world",
                0,
                64,
                0,
                permissions,
                now
        );
    }

    private static DialogueSettings settings() {
        return new DialogueSettings(true, 8, false, 8000, 0, 120, 8, 12, 0, 20, 200, true, 300, true, true, 1);
    }

    private static CharacterAction action(int cooldown, int daily, String permission) {
        return new CharacterAction("give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, cooldown, daily, permission);
    }
}
