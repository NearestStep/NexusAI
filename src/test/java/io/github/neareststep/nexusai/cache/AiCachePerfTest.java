package io.github.neareststep.nexusai.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight before/after style timings for the shared response cache.
 * Numbers are printed for the PR description; the suite only checks a generous bound.
 */
class AiCachePerfTest {

    @Test
    void putGetStayUnderOneMillisecondPerOpOnAverage() {
        int ops = 5_000;
        AiCache entryCap = new AiCache(Duration.ofMinutes(5), 2_000, false, true, 0L);
        long entryNanos = runPutGet(entryCap, ops, "entry-");
        AiCache memoryCap = new AiCache(Duration.ofMinutes(5), 2_000, false, true, 2_000_000L);
        long memoryNanos = runPutGet(memoryCap, ops, "mem-");
        double entryUs = entryNanos / 1_000.0 / ops;
        double memoryUs = memoryNanos / 1_000.0 / ops;
        System.out.printf(Locale.ROOT,
                "AiCache put+get avg: entry-cap=%.2f µs, memory-cap=%.2f µs (%d ops)%n",
                entryUs, memoryUs, ops);
        assertTrue(entryUs < 1_000.0, "entry-cap avg µs=" + entryUs);
        assertTrue(memoryUs < 1_000.0, "memory-cap avg µs=" + memoryUs);
    }

    private static long runPutGet(AiCache cache, int ops, String prefix) {
        String payload = "pong-".repeat(8);
        long started = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            String key = prefix + i;
            cache.put(key, payload);
            cache.get(key);
        }
        return System.nanoTime() - started;
    }
}