package io.github.neareststep.nexusai.pool;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolStoreTest {

    @Test
    void saveAndLoadRoundTripTrimsToTheConfiguredSize() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-store"), true);
            AiPool source = new AiPool();
            source.add("Say: \"hi\"", "one");
            source.add("Say: \"hi\"", "two");
            source.add("Say: \"hi\"", "three");
            source.add("other", "skip-me");
            store.saveNow(source, Map.of("Say: \"hi\"", 2));

            AiPool loaded = new AiPool();
            store.load(loaded, Map.of("Say: \"hi\"", 2));
            assertEquals(List.of("one", "two"), loaded.copy("Say: \"hi\""));
            assertEquals(0, loaded.size("other"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void debouncedSaveWritesAfterTheQuietPeriod() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-debounce").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(150), Logger.getLogger("pool-store"), true);
            AiPool pool = new AiPool();
            pool.add("tip", "ready");
            store.markDirty(pool, Map.of("tip", 3));
            assertTrue(!file.isFile() || Files.readString(file.toPath()).isBlank() || !Files.readString(file.toPath()).contains("ready"));
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                if (file.isFile() && Files.readString(file.toPath()).contains("ready")) {
                    return;
                }
                Thread.sleep(20);
            }
            assertTrue(file.isFile() && Files.readString(file.toPath()).contains("ready"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void multilinePromptRoundTrips() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-lines").resolve("pool.yml").toFile();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            PoolStore store = new PoolStore(file, scheduler, Duration.ofMillis(200), Logger.getLogger("pool-store"), true);
            String prompt = "Stay fed\nSleep";
            AiPool source = new AiPool();
            source.add(prompt, "one");
            store.saveNow(source, Map.of(prompt, 2));

            AiPool loaded = new AiPool();
            store.load(loaded, Map.of(prompt, 2));
            assertEquals(List.of("one"), loaded.copy(prompt));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void disabledStoreDoesNotCreateAFile() throws Exception {
        File file = Files.createTempDirectory("nexusai-pool-off").resolve("pool.yml").toFile();
        PoolStore store = PoolStore.disabled();
        AiPool pool = new AiPool();
        pool.add("tip", "x");
        store.markDirty(pool, Map.of("tip", 1));
        store.flush(pool, Map.of("tip", 1));
        store.load(pool, Map.of("tip", 1));
        assertTrue(!file.isFile());
    }
}
