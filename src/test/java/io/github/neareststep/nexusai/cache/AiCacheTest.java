package io.github.neareststep.nexusai.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCacheTest {

    @Test
    void putAndGet() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        cache.put("k", "v");
        assertEquals("v", cache.get("k").orElseThrow());
        assertEquals(1, cache.size());
    }

    @Test
    void expiresAfterTtl() throws InterruptedException {
        AiCache cache = new AiCache(Duration.ofMillis(50), 100);
        cache.put("k", "v");
        Thread.sleep(80);
        assertTrue(cache.get("k").isEmpty());
    }
}
