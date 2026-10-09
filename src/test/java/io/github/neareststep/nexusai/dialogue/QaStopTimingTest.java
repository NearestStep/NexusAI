package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaStopTimingTest {

    @Test
    void pausedLoadStopKeepsDiskLinesAndStaysNearTheJoin() throws Exception {
        Logger logger = Logger.getLogger("qa-stop");
        logger.setUseParentHandlers(false);
        Path file = Files.createTempDirectory("qa-stop").resolve("dialogue-memory.yml");
        MemoryStore source = new MemoryStore();
        UUID first = UUID.randomUUID();
        source.append(first, "npc_a", "user", "DISKA1", 70L, 64, 1_000_000, 0L);
        for (int i = 0; i < 2500; i++) {
            UUID player = new UUID(0x1234L, i);
            for (int character = 0; character < 4; character++) {
                for (int line = 0; line < 16; line++) {
                    source.append(player, "npc_" + character, "user",
                            "filler " + i + " " + character + " " + line + " lorem ipsum dolor sit amet",
                            70L, 64, 1_000_000, 0L);
                }
            }
        }
        source.save(file.toFile(), logger, true);
        long size = Files.size(file);
        int cpus = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        System.out.println("qa-shaped file bytes=" + size + " cpus=" + cpus + " heapMax=" + heap);
        for (int rep = 0; rep < 3; rep++) {
            Path copy = file.resolveSibling("r" + rep + ".yml");
            Files.copy(file, copy);
            MemoryStore store = new MemoryStore();
            store.append(first, "npc_a", "user", "late", 70L, 8, 8000, 0L);
            store.append(first, "npc_d", "user", "new char", 70L, 8, 8000, 0L);
            UUID created = UUID.randomUUID();
            store.append(created, "npc_d", "user", "new player", 70L, 8, 8000, 0L);
            DialogueMemoryPersistence files = new DialogueMemoryPersistence(
                    store, copy::toFile, () -> persisting(true), List::of, logger, System::currentTimeMillis);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            MemoryStore.pauseDuringLoad = () -> {
                inside.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            long elapsed;
            try {
                files.onReload();
                assertTrue(inside.await(30, TimeUnit.SECONDS));
                long started = System.nanoTime();
                files.shutdown();
                elapsed = (System.nanoTime() - started) / 1_000_000L;
            } finally {
                MemoryStore.pauseDuringLoad = null;
                release.countDown();
                Thread pending = files.diskLoader();
                if (pending != null) {
                    pending.join(60_000L);
                }
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(copy.toFile());
            String kept = String.valueOf(yaml.get("entries." + first + ".npc_a.lines"));
            System.out.println("qa-shaped stop rep=" + rep + " ms=" + elapsed
                    + " bytes=" + size + " cpus=" + cpus + " heapMax=" + heap);
            assertTrue(yaml.get("entries." + first + ".npc_d") != null, "new character missing");
            assertTrue(kept.contains("DISKA1"), kept);
            assertFalse(kept.contains("late"), kept);
            assertTrue(elapsed < 2_000L, "stop took " + elapsed + " ms, cpus=" + cpus + ", heapMax=" + heap);
        }
    }

    private static DialogueSettings persisting(boolean summaries) {
        DialogueSettings defaults = DialogueSettings.defaults();
        return new DialogueSettings(
                defaults.dialogueEnabled(),
                defaults.memoryTurns(),
                true,
                defaults.memoryMaxChars(),
                defaults.memoryExpiryHours(),
                defaults.sessionTimeoutSeconds(),
                defaults.leaveRadius(),
                defaults.maxRepliesPerSession(),
                defaults.messageCooldownMillis(),
                defaults.conversationsPerPlayerPerDay(),
                defaults.maxMessageLength(),
                defaults.cacheGreeting(),
                defaults.greetingCacheSeconds(),
                defaults.actionsEnabled(),
                defaults.actionLog(),
                defaults.maxActionsPerReply(),
                summaries,
                defaults.summaryThresholdTurns(),
                defaults.summaryMaxChars(),
                defaults.summaryMaxTokens(),
                defaults.summaryProvider(),
                defaults.summaryModel());
    }
}
