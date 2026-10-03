package io.github.neareststep.nexusai.dialogue;

import io.github.neareststep.nexusai.ai.AiErrorKind;
import io.github.neareststep.nexusai.ai.AiRequestException;
import io.github.neareststep.nexusai.ai.HttpPool;
import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.context.ContextBlock;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

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
    void markupOnlyReplyFallsBackWithoutLookingLikeAFailure() {
        DialogueEngine engine = engine(reply -> {
            throw new AiRequestException(AiErrorKind.MARKUP_ONLY, 200, PlayerInput.MARKUP_ONLY, null);
        });
        TalkResult result = engine.talk(request("hi", false, List.of(), settings(), 1_000L, node -> true));
        assertEquals(TalkCode.REPLY, result.code());
        assertEquals("...", result.text());
        assertEquals(1, calls.get());
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

    @Test
    void summaryFiresOnceAndTheNextSystemCarriesTheWrappedText() {
        Harness harness = new Harness(Runnable::run, null);
        DialogueSettings settings = summarySettings(2, 2, 0);
        for (int i = 0; i < 4; i++) {
            TalkResult result = harness.engine.talk(request("m" + i, false, List.of(), settings, 10_000L + i, node -> true));
            assertEquals(TalkCode.REPLY, result.code());
            assertEquals("ok", result.text());
        }
        long summaries = harness.calls.stream().filter(call -> call.kind() == DialogueEngine.CallKind.SUMMARY).count();
        assertEquals(1L, summaries);
        assertEquals(1, harness.stats.ok());
        assertEquals(0, harness.stats.failed());
        harness.engine.talk(request("m4", false, List.of(), settings, 20_000L, node -> true));
        DialogueEngine.ModelCall next = harness.calls.get(harness.calls.size() - 1);
        assertEquals(DialogueEngine.CallKind.DIALOGUE, next.kind());
        assertTrue(next.system().contains(DialogueSummary.HEADER));
        assertTrue(next.system().contains(PlayerInput.OPEN));
        assertTrue(next.system().contains("They talked."));
        assertTrue(next.system().contains(PlayerInput.CLOSE));
        assertEquals(1L, harness.calls.stream().filter(call -> call.kind() == DialogueEngine.CallKind.SUMMARY).count());
    }

    @Test
    void contextBlockStaysAfterTheSummaryAndBeforeTheFormatInstruction() {
        Harness harness = new Harness(Runnable::run, null);
        harness.memory.completeSummary(
                player, "blacksmith", "FROM MEMORY", 1L, harness.memory.get(player, "blacksmith").epoch());
        String instruction = "Reply in one short sentence.";
        String system = "You are Bram.\n\n"
                + DialogueSummary.block("They asked about the harbor.")
                + "\n\n" + instruction;
        String spliced = ContextBlock.spliceSystem(system, instruction, PlayerInput.wrap("gold: 12"));
        DialogueEngine.TalkRequest request = new DialogueEngine.TalkRequest(
                player, "Steve", "blacksmith", "hello", false, false, false,
                spliced, "...", DialogueProfile.absent(), List.of(), summarySettings(8, 2, 0),
                GenerationOverrides.none(), "chat", "world", 0, 64, 0, node -> true, 10_000L);
        assertEquals(TalkCode.REPLY, harness.engine.talk(request).code());
        String sent = harness.calls.get(harness.calls.size() - 1).system();
        int summaryAt = sent.indexOf(DialogueSummary.HEADER.trim());
        int contextAt = sent.indexOf(ContextBlock.HEADER.trim());
        int instructionAt = sent.lastIndexOf(instruction);
        assertTrue(summaryAt >= 0 && contextAt > summaryAt && instructionAt > contextAt, sent);
        assertEquals(summaryAt, sent.lastIndexOf(DialogueSummary.HEADER.trim()));
        assertFalse(sent.contains("FROM MEMORY"));
        assertTrue(sent.contains("gold: 12"));
    }

    @Test
    void onlyOneSummaryIsInFlight() {
        List<Runnable> queued = new ArrayList<>();
        Harness held = new Harness(queued::add, null);
        DialogueSettings settings = summarySettings(2, 2, 0);
        for (int i = 0; i < 4; i++) {
            held.engine.talk(request("m" + i, false, List.of(), settings, 10_000L + i, node -> true));
        }
        assertEquals(1, queued.size());
        assertTrue(held.memory.get(player, "blacksmith").summaryInFlight());
        held.engine.talk(request("later", false, List.of(), settings, 30_000L, node -> true));
        held.engine.talk(request("after", false, List.of(), settings, 40_000L, node -> true));
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertFalse(held.memory.get(player, "blacksmith").summaryInFlight());
        assertEquals("They talked.", held.memory.summary(player, "blacksmith", 50_000L, 0L));
    }

    @Test
    void refusedSummaryClearsTheBufferAndKeepsThePreviousSummary() {
        Harness harness = new Harness(Runnable::run, call -> {
            if (call.kind() == DialogueEngine.CallKind.SUMMARY) {
                throw new AiRequestException(AiErrorKind.LOCAL_LIMIT, 0, "Local rate limit reached", null);
            }
            return DialogueEngine.ModelReply.text("still here");
        });
        harness.memory.append(player, "blacksmith", "user", "old-1", 1L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "assistant", "a1", 2L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "user", "old-2", 3L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "assistant", "a2", 4L, 2, 8000, 0L, true);
        harness.memory.completeSummary(player, "blacksmith", "old facts", 5L, harness.memory.get(player, "blacksmith").epoch());
        DialogueSettings settings = summarySettings(2, 1, 0);
        TalkResult result = harness.engine.talk(request("new", false, List.of(), settings, 10_000L, node -> true));
        assertEquals(TalkCode.REPLY, result.code());
        assertEquals("still here", result.text());
        assertEquals("old facts", harness.memory.summary(player, "blacksmith", 10_000L, 0L));
        assertTrue(harness.memory.get(player, "blacksmith").pendingView().isEmpty());
        assertEquals(1, harness.stats.failed());
        assertEquals(0, harness.stats.ok());
        assertEquals("on (0 ok, 1 failed today)", harness.stats.text(true, 10_000L));
        assertEquals("off", harness.stats.text(false, 10_000L));
    }

    @Test
    void httpQueueFullClearsTheFoldBufferAndKeepsTheSummary() {
        Harness harness = new Harness(Runnable::run, call -> {
            if (call.kind() == DialogueEngine.CallKind.SUMMARY) {
                throw HttpPool.queueFull(null);
            }
            return DialogueEngine.ModelReply.text("still here");
        });
        harness.memory.append(player, "blacksmith", "user", "old-1", 1L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "assistant", "a1", 2L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "user", "old-2", 3L, 2, 8000, 0L, true);
        harness.memory.append(player, "blacksmith", "assistant", "a2", 4L, 2, 8000, 0L, true);
        harness.memory.completeSummary(player, "blacksmith", "old facts", 5L, harness.memory.get(player, "blacksmith").epoch());
        DialogueSettings settings = summarySettings(2, 1, 0);
        TalkResult result = harness.engine.talk(request("new", false, List.of(), settings, 10_000L, node -> true));
        assertEquals(TalkCode.REPLY, result.code());
        assertEquals("still here", result.text());
        assertEquals("old facts", harness.memory.summary(player, "blacksmith", 10_000L, 0L));
        assertTrue(harness.memory.get(player, "blacksmith").pendingView().isEmpty());
        assertEquals(1, harness.stats.failed());
        assertEquals(0, harness.stats.ok());
    }

    @Test
    void disabledSummaryMatchesTheUnmodifiedTranscript() {
        List<String> plain = new ArrayList<>();
        List<String> off = new ArrayList<>();
        DialogueEngine plainEngine = engine(reply -> {
            plain.add(reply.system() + "\n" + reply.messages());
            return DialogueEngine.ModelReply.text("ok");
        });
        DialogueEngine offEngine = engine(reply -> {
            off.add(reply.system() + "\n" + reply.messages());
            return DialogueEngine.ModelReply.text("ok");
        });
        DialogueSettings turns = new DialogueSettings(
                true, 2, false, 8000, 0, 0, 0, 12, 0, 0, 200, false, 300, false, false, 1);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("dialogue.summary.enabled", false);
        yaml.set("dialogue.memory-turns", 2);
        yaml.set("dialogue.message-cooldown-millis", 0);
        yaml.set("dialogue.conversations-per-player-per-day", 0);
        yaml.set("dialogue.cache-greeting", false);
        DialogueSettings explicit = DialogueSettings.read(yaml);
        for (int i = 0; i < 4; i++) {
            plainEngine.talk(request("m" + i, false, List.of(), turns, 10_000L + i, node -> true));
            offEngine.talk(request("m" + i, false, List.of(), explicit, 10_000L + i, node -> true));
        }
        assertEquals(plain, off);
        assertFalse(plain.get(plain.size() - 1).contains(DialogueSummary.HEADER));
        assertEquals(2, countUsers(plain.get(plain.size() - 1)));
    }

    @Test
    void summaryDoesNotSpendTheConversationCap() {
        Harness harness = new Harness(Runnable::run, null);
        DialogueSettings settings = new DialogueSettings(
                true, 1, false, 8000, 0, 0, 0, 12, 0, 3, 200, false, 300, false, false, 1,
                true, 1, 400, 200, "", "");
        for (int i = 0; i < 3; i++) {
            assertEquals(TalkCode.REPLY, harness.engine.talk(
                    request("m" + i, false, List.of(), settings, 10_000L + i * 10L, node -> true)).code());
        }
        assertTrue(harness.stats.ok() >= 1);
    }

    @Test
    void summaryCountersResetAtLocalMidnight() {
        SummaryStats stats = new SummaryStats(ZoneId.of("UTC"));
        long first = Instant.parse("2026-10-03T12:00:00Z").toEpochMilli();
        long next = Instant.parse("2026-10-04T00:05:00Z").toEpochMilli();
        stats.success(first);
        stats.failure(first);
        assertEquals("on (1 ok, 1 failed today)", stats.text(true, first));
        stats.success(next);
        assertEquals("on (1 ok, 0 failed today)", stats.text(true, next));
        stats.reset();
        assertEquals("on (0 ok, 0 failed today)", stats.text(true, next));
    }

    private static int countUsers(String snapshot) {
        int users = 0;
        int from = 0;
        while (from >= 0) {
            int at = snapshot.indexOf("role=user", from);
            if (at < 0) {
                return users;
            }
            users++;
            from = at + 1;
        }
        return users;
    }

    private static DialogueSettings summarySettings(int memoryTurns, int threshold, int conversations) {
        return new DialogueSettings(
                true, memoryTurns, false, 8000, 0, 0, 0, 30, 0, conversations, 200, false, 300, false, false, 1,
                true, threshold, 400, 200, "", "");
    }

    private final class Harness {
        final MemoryStore memory = new MemoryStore();
        final SummaryStats stats = new SummaryStats(ZoneId.of("UTC"));
        final List<DialogueEngine.ModelCall> calls = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        final DialogueEngine engine;

        Harness(java.util.concurrent.Executor executor, DialogueEngine.DialogueModel override) {
            DialogueEngine.DialogueModel model = call -> {
                calls.add(call);
                if (override != null) {
                    return override.complete(call);
                }
                if (call.kind() == DialogueEngine.CallKind.SUMMARY) {
                    return DialogueEngine.ModelReply.text("They talked.");
                }
                return DialogueEngine.ModelReply.text("ok");
            };
            DialogueSummary summaries = new DialogueSummary(
                    memory, model, stats, Logger.getLogger("summary-test"), executor);
            engine = new DialogueEngine(
                    memory,
                    new SessionBook(),
                    new ActionGate(),
                    new DialogueBudget(),
                    new GreetingCache(),
                    model,
                    (id, action, command) -> "ran",
                    ActionLog.noop(),
                    ZoneId.of("UTC"),
                    summaries
            );
        }
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
