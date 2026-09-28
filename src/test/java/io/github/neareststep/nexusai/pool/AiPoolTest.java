package io.github.neareststep.nexusai.pool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPoolTest {

    @Test
    void pollAddAndSize() {
        AiPool pool = new AiPool();
        assertEquals(0, pool.size("p"));
        assertTrue(pool.poll("p").isEmpty());

        pool.add("p", "a");
        pool.add("p", "b");
        assertEquals(2, pool.size("p"));
        assertEquals(Optional.of("a"), pool.poll("p"));
        assertEquals(Optional.of("b"), pool.poll("p"));
        assertEquals(0, pool.size("p"));
        assertTrue(pool.prompts().contains("p"));
    }

    @Test
    void concurrentPollAcrossHundredThreads() throws Exception {
        AiPool pool = new AiPool();
        int total = 1000;
        for (int i = 0; i < total; i++) {
            pool.add("prompt", "v" + i);
        }

        AtomicInteger taken = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(100);
        ExecutorService executor = Executors.newFixedThreadPool(100);
        try {
            for (int t = 0; t < 100; t++) {
                executor.submit(() -> {
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        while (pool.poll("prompt").isPresent()) {
                            taken.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(total, taken.get());
        assertEquals(0, pool.size("prompt"));
    }

    @Test
    void duplicateTextIsStoredOnce() {
        AiPool pool = new AiPool();
        assertTrue(pool.add("p", "DUPLICATE-ANSWER"));
        assertFalse(pool.add("p", "DUPLICATE-ANSWER"));
        assertEquals(1, pool.size("p"));
        assertEquals(List.of("DUPLICATE-ANSWER"), pool.copy("p"));
        pool.replace("p", List.of("DUPLICATE-ANSWER", "DUPLICATE-ANSWER", "other"));
        assertEquals(List.of("DUPLICATE-ANSWER", "DUPLICATE-ANSWER", "other"), pool.copy("p"));
        assertFalse(pool.add("p", "DUPLICATE-ANSWER"));
        assertTrue(pool.add("p", "Hello {player_name}!", true));
        assertTrue(pool.add("p", "Hello {player_name}!", true));
        assertEquals(2, pool.copy("p").stream().filter("Hello {player_name}!"::equals).count());
    }

    @Test
    void polledTextIsNotAcceptedAgain() {
        AiPool pool = new AiPool();
        assertTrue(pool.add("p", "DUPLICATE-ANSWER"));
        assertEquals(Optional.of("DUPLICATE-ANSWER"), pool.poll("p"));
        assertEquals(0, pool.size("p"));
        assertFalse(pool.add("p", "DUPLICATE-ANSWER"));
        assertEquals(0, pool.size("p"));
        assertTrue(pool.add("p", "other"));
        assertEquals(List.of("other"), pool.copy("p"));
    }

    @Test
    void concurrentAddIsVisible() throws Exception {
        AiPool pool = new AiPool();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(100);
        ExecutorService executor = Executors.newFixedThreadPool(100);
        try {
            for (int t = 0; t < 100; t++) {
                int id = t;
                executor.submit(() -> {
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        pool.add("p", "a" + id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(100, pool.size("p"));
        List<String> values = new ArrayList<>();
        pool.poll("p").ifPresent(values::add);
        while (true) {
            Optional<String> next = pool.poll("p");
            if (next.isEmpty()) {
                break;
            }
            values.add(next.get());
        }
        assertEquals(100, values.size());
    }
}
