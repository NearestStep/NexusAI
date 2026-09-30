package io.github.neareststep.nexusai.ai;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin API keys. A key that returns HTTP 401 or 429 is skipped until {@code skipUntil}.
 */
public final class KeyRing {

    private final List<String> keys;
    private final AtomicInteger cursor = new AtomicInteger();
    private final ConcurrentHashMap<String, Long> skipUntil = new ConcurrentHashMap<>();

    public KeyRing(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            this.keys = List.of();
        } else {
            this.keys = List.copyOf(keys);
        }
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public List<String> keys() {
        return keys;
    }

    /**
     * @return the next usable key, {@code ""} when this provider has no keys, or {@code null} when every key is skipped
     */
    public String acquire(long nowMillis) {
        return acquire(nowMillis, false);
    }

    /**
     * @param ignoreSkip when true, return the next key even if it is inside a skip window.
     *                   Probes use this so a test still reaches the provider.
     */
    public String acquire(long nowMillis, boolean ignoreSkip) {
        if (keys.isEmpty()) {
            return "";
        }
        int size = keys.size();
        int start = Math.floorMod(cursor.getAndIncrement(), size);
        if (ignoreSkip) {
            return keys.get(Math.floorMod(start, size));
        }
        for (int i = 0; i < size; i++) {
            String key = keys.get(Math.floorMod(start + i, size));
            if (nowMillis >= skipUntil.getOrDefault(key, 0L)) {
                return key;
            }
        }
        return null;
    }

    public void skip(String key, long untilMillis) {
        if (key == null || key.isEmpty()) {
            return;
        }
        skipUntil.merge(key, untilMillis, Math::max);
    }

    public boolean hasAvailable(long nowMillis) {
        if (keys.isEmpty()) {
            return false;
        }
        for (String key : keys) {
            if (nowMillis >= skipUntil.getOrDefault(key, 0L)) {
                return true;
            }
        }
        return false;
    }

    public long nextReadyAt(long nowMillis) {
        if (keys.isEmpty()) {
            return nowMillis;
        }
        long soonest = Long.MAX_VALUE;
        for (String key : keys) {
            soonest = Math.min(soonest, skipUntil.getOrDefault(key, nowMillis));
        }
        return soonest == Long.MAX_VALUE ? nowMillis : soonest;
    }
}
