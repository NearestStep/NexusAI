package io.github.neareststep.nexusai.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCacheTest {

    @Test
    void cachedRepliesLoseSectionSignsAndAmpersandCodes() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        cache.put("color", "COLOR#5 §cSECRED stray§ §zZ &cAMPRED &x&f&f&0&0&0&0HEX");
        assertEquals("COLOR#5 SECRED stray zZ AMPRED HEX", cache.get("color").orElseThrow());
        assertFalse(cache.get("color").orElseThrow().contains("§"));
        assertFalse(cache.get("color").orElseThrow().contains("&c"));
    }

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

    @Test
    void isFreshRightAfterPut() {
        AiCache cache = new AiCache(Duration.ofSeconds(10), 100);
        cache.put("k", "v");
        assertTrue(cache.isFresh("k"));
    }

    @Test
    void isFreshFalseAfterEightyPercentOfTtl() throws InterruptedException {
        AiCache cache = new AiCache(Duration.ofMillis(100), 100);
        cache.put("k", "v");
        Thread.sleep(90);
        assertFalse(cache.isFresh("k"));
    }

    @Test
    void legacyPutLeavesProviderAndModelEmpty() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        cache.put("k", "hello");
        cache.put("short", "later", Duration.ofMinutes(1));
        AiCache.CachedAnswer legacy = cache.lookup("k").orElseThrow();
        assertEquals("hello", legacy.text());
        assertEquals("", legacy.providerId());
        assertEquals("", legacy.model());
        AiCache.CachedAnswer timed = cache.lookup("short").orElseThrow();
        assertEquals("", timed.providerId());
        assertEquals("", timed.model());
    }

    @Test
    void putWithProviderAndModelReturnsThemOnLookup() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        cache.put("k", "hello", "openai", "gpt-4o-mini");
        AiCache.CachedAnswer hit = cache.lookup("k").orElseThrow();
        assertEquals("hello", hit.text());
        assertEquals("openai", hit.providerId());
        assertEquals("gpt-4o-mini", hit.model());
        assertEquals("hello", cache.get("k").orElseThrow());
    }

    @Test
    void perEntryTtlOverridesTheCacheDefault() throws InterruptedException {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100);
        cache.put("short", "v", Duration.ofMillis(50));
        cache.put("long", "v");
        Thread.sleep(80);
        assertTrue(cache.get("short").isEmpty());
        assertEquals("v", cache.get("long").orElseThrow());
    }

    @Test
    void disabledCacheIgnoresReadsAndWrites() {
        AiCache cache = new AiCache(Duration.ofMinutes(5), 100, false, false, 0L);
        cache.put("k", "v");
        assertTrue(cache.get("k").isEmpty());
        assertEquals(0, cache.size());
        assertFalse(cache.enabled());
    }

    @Test
    void memoryLimitEvictsHeavyEntries() {
        long budget = 200L;
        AiCache cache = new AiCache(Duration.ofMinutes(5), 10_000, false, true, budget);
        cache.put("a", "x".repeat(80));
        cache.put("b", "y".repeat(80));
        cache.put("c", "z".repeat(80));
        assertTrue(cache.size() < 3, "expected eviction under memory budget, size=" + cache.size());
        int present = 0;
        for (String key : new String[]{"a", "b", "c"}) {
            if (cache.get(key).isPresent()) {
                present++;
            }
        }
        assertTrue(present < 3, "present=" + present);
        assertTrue(AiCache.weightOf("a", new AiCache.CachedAnswer("x".repeat(80), "", "", "")) > budget / 2);
    }
}
