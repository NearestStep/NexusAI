package io.github.neareststep.nexusai.budget;

import io.github.neareststep.nexusai.config.QueueEntryConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelQueueAtomicTest {

    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    @TempDir
    Path dir;

    @Test
    void aLegacyUsageFileSurvivesAnAtomicRewrite() throws Exception {
        Path file = dir.resolve("usage.yml");
        String legacy = """
                config-version: 1
                day: "2026-09-29"
                providers:
                  openai: 4
                  groq: 1
                entries:
                - id: "0|openai|gpt-4o-mini"
                  provider: openai
                  model: gpt-4o-mini
                  requests: 4
                  rejected: 2
                - id: "1|groq|llama"
                  provider: groq
                  model: llama
                  requests: 1
                  rejected: 0
                moderation:
                  checks: 3
                  flags: 1
                fallback:
                - id: "-1|gemini|flash"
                  provider: gemini
                  model: flash
                  requests: 2
                  rejected: 1
                """;
        Files.writeString(file, legacy, StandardCharsets.UTF_8);
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 9, 29));
        ModelQueue queue = queue(file, day);
        assertEquals(4, queue.requestsToday(0));
        assertEquals(1, queue.requestsToday(1));
        assertEquals(4, queue.providerRequests("openai"));
        assertEquals(2, queue.status(1_000L).get(0).rejected());
        assertEquals(3, queue.moderationChecks());
        assertEquals(1, queue.moderationFlags());
        assertEquals(2, queue.fallbackStatus("gemini", "flash", 1_000L).requestsToday());
        assertEquals(1, queue.fallbackStatus("gemini", "flash", 1_000L).rejected());

        queue.save();
        assertTrue(temps().isEmpty(), temps().toString());
        String rewritten = Files.readString(file);
        assertTrue(rewritten.contains("requests:"), rewritten);
        assertTrue(rewritten.contains("rejected:"), rewritten);
        assertTrue(rewritten.contains("config-version:"), rewritten);
        assertFalse(rewritten.contains(".tmp"), rewritten);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(rewritten);
        assertEquals("2026-09-29", yaml.getString("day"));
        assertEquals(4, yaml.getInt("providers.openai"));
        assertEquals(1, yaml.getInt("providers.groq"));
        assertEquals(3, yaml.getInt("moderation.checks"));
        assertEquals(1, yaml.getInt("moderation.flags"));
        assertEquals(2, yaml.getMapList("entries").size());
        assertEquals("0|openai|gpt-4o-mini", String.valueOf(yaml.getMapList("entries").getFirst().get("id")));
        assertEquals(4, ((Number) yaml.getMapList("entries").getFirst().get("requests")).intValue());
        assertEquals(2, ((Number) yaml.getMapList("entries").getFirst().get("rejected")).intValue());
        assertEquals(1, yaml.getMapList("fallback").size());

        ModelQueue reloaded = queue(file, day);
        assertEquals(4, reloaded.requestsToday(0));
        assertEquals(1, reloaded.requestsToday(1));
        assertEquals(2, reloaded.status(1_000L).get(0).rejected());
        assertEquals(3, reloaded.moderationChecks());
        assertEquals(1, reloaded.moderationFlags());
        assertEquals(2, reloaded.fallbackStatus("gemini", "flash", 1_000L).requestsToday());
        assertEquals(1, reloaded.fallbackStatus("gemini", "flash", 1_000L).rejected());
    }

    @Test
    void aNewUsageFileIsOwnerReadWriteAndAnExistingModeIsKept() throws Exception {
        Path created = dir.resolve("fresh").resolve("usage.yml");
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 9, 29));
        ModelQueue queue = queue(created, day);
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(temps(created.getParent()).isEmpty());
        PosixFileAttributeView view = Files.getFileAttributeView(created, PosixFileAttributeView.class);
        if (view != null) {
            assertEquals(OWNER_ONLY, view.readAttributes().permissions());
        }

        Path existing = dir.resolve("kept").resolve("usage.yml");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "day: \"2026-09-29\"\n");
        if (view == null) {
            return;
        }
        Set<PosixFilePermission> shared = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(existing, shared);
        ModelQueue second = queue(existing, day);
        assertTrue(second.tryConsume(0, 1_000L));
        assertEquals(shared, Files.getPosixFilePermissions(existing));
    }

    @Test
    void startupRemovesOnlyUsageTempFiles() throws Exception {
        Path file = dir.resolve("usage.yml");
        Path usageTemp = dir.resolve("usage.yml." + UUID.randomUUID() + ".tmp");
        Path tokenTemp = dir.resolve("token-usage.yml." + UUID.randomUUID() + ".tmp");
        Files.writeString(usageTemp, "stale");
        Files.writeString(tokenTemp, "leave");
        queue(file, new AtomicReference<>(LocalDate.of(2026, 9, 29)));
        assertFalse(Files.exists(usageTemp));
        assertTrue(Files.exists(tokenTemp));
    }

    @Test
    void aQueuedSaveCannotReplaceTheFlushedFile() throws Exception {
        Path file = dir.resolve("usage.yml");
        AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 9, 29));
        ModelQueue queue = queue(file, day);
        List<Runnable> pending = new ArrayList<>();
        queue.saveExecutor(pending::add);
        assertTrue(queue.tryConsume(0, 1_000L));
        assertTrue(queue.tryConsume(0, 1_000L));
        assertEquals(2, pending.size());
        queue.flushAndClose();
        for (Runnable late : pending) {
            late.run();
        }
        assertTrue(temps().isEmpty(), temps().toString());
        ModelQueue reloaded = queue(file, day);
        assertEquals(2, reloaded.requestsToday(0));
        assertEquals(2, reloaded.providerRequests("openai"));
    }

    private static ModelQueue queue(Path file, AtomicReference<LocalDate> day) {
        return new ModelQueue(
                List.of(
                        new QueueEntryConfig("openai", "gpt-4o-mini", 0),
                        new QueueEntryConfig("groq", "llama", 0)
                ),
                0,
                60_000L,
                300_000L,
                file.toFile(),
                () -> 0L,
                day::get,
                ZoneId.of("UTC"),
                Logger.getLogger("usage-atomic-" + file.getFileName())
        );
    }

    private List<String> temps() throws Exception {
        return temps(dir);
    }

    private static List<String> temps(Path directory) throws Exception {
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".tmp"))
                    .forEach(names::add);
        }
        return names;
    }
}
