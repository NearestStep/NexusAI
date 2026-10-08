package io.github.neareststep.nexusai.event;

import io.github.neareststep.nexusai.config.GenerationOverrides;
import io.github.neareststep.nexusai.dialogue.ActionExecution;
import io.github.neareststep.nexusai.dialogue.ActionGate;
import io.github.neareststep.nexusai.dialogue.ActionLog;
import io.github.neareststep.nexusai.dialogue.CharacterAction;
import io.github.neareststep.nexusai.dialogue.DialogueBudget;
import io.github.neareststep.nexusai.dialogue.DialogueEngine;
import io.github.neareststep.nexusai.dialogue.DialogueProfile;
import io.github.neareststep.nexusai.dialogue.DialogueSettings;
import io.github.neareststep.nexusai.dialogue.GreetingCache;
import io.github.neareststep.nexusai.dialogue.MemoryStore;
import io.github.neareststep.nexusai.dialogue.SessionBook;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionEventTest {

    @Test
    void expiredFlagSkipsTheEventAndTheCommand() {
        AtomicBoolean expired = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        String result = ActionExecution.execute(
                expired,
                () -> {
                    calls.incrementAndGet();
                    return false;
                },
                () -> {
                    calls.addAndGet(10);
                    return "ran";
                });
        assertNull(result);
        assertEquals(0, calls.get());
    }

    @Test
    void cancelledEventDoesNotRunTheCommand() {
        AtomicBoolean expired = new AtomicBoolean();
        AtomicInteger commands = new AtomicInteger();
        String result = ActionExecution.execute(expired, () -> true, () -> {
            commands.incrementAndGet();
            return "ran";
        });
        assertEquals(ActionExecution.BLOCKED, result);
        assertEquals(0, commands.get());
    }

    @Test
    void expiryDuringTheEventSkipsTheCommand() {
        AtomicBoolean expired = new AtomicBoolean();
        AtomicInteger commands = new AtomicInteger();
        String result = ActionExecution.execute(expired, () -> {
            expired.set(true);
            return false;
        }, () -> {
            commands.incrementAndGet();
            return "ran";
        });
        assertNull(result);
        assertEquals(0, commands.get());
    }

    @Test
    void blockedActionDoesNotSpendCooldown() {
        UUID player = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        List<String> notes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        Logger logger = Logger.getLogger("action-block-" + player);
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    lines.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        DialogueEngine engine = new DialogueEngine(
                new MemoryStore(),
                new SessionBook(),
                new ActionGate(),
                new DialogueBudget(),
                new GreetingCache(),
                call -> {
                    if (!call.tools().isEmpty()) {
                        return new DialogueEngine.ModelReply("", List.of("give_iron"), false);
                    }
                    notes.add(call.system());
                    return DialogueEngine.ModelReply.text("done");
                },
                (id, action, command) -> {
                    runs.incrementAndGet();
                    return ActionExecution.BLOCKED;
                },
                new ActionLog(logger, null, () -> false),
                ZoneId.of("UTC"));
        CharacterAction action = new CharacterAction(
                "give_iron", "Give one iron ingot.", "give {player} iron_ingot 1", true, 30, 0, null);
        DialogueSettings settings = new DialogueSettings(
                true, 8, false, 8000, 0, 120, 8, 12, 0, 20, 200, true, 300, true, true, 1);
        engine.talk(request(player, "one", List.of(action), settings, 1_000L));
        engine.talk(request(player, "two", List.of(action), settings, 2_000L));
        assertEquals(2, runs.get());
        assertTrue(notes.get(0).contains("refused: blocked by server"), notes.toString());
        assertTrue(lines.stream().anyMatch(line -> line.contains("refused: blocked by server")), lines.toString());
        assertTrue(lines.stream().noneMatch(line -> line.contains("plugin=")), lines.toString());
    }

    private static DialogueEngine.TalkRequest request(
            UUID player,
            String message,
            List<CharacterAction> actions,
            DialogueSettings settings,
            long now
    ) {
        return new DialogueEngine.TalkRequest(
                player,
                "Steve",
                "blacksmith",
                message,
                false,
                false,
                false,
                "You are Bram.",
                "...",
                DialogueProfile.absent(),
                actions,
                settings,
                GenerationOverrides.none(),
                "chat",
                "world",
                0,
                64,
                0,
                node -> true,
                now);
    }
}
