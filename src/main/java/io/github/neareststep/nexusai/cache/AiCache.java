package io.github.neareststep.nexusai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTL-bounded response cache backed by Caffeine.
 */
public final class AiCache {

    private final Cache<String, String> cache;
    private final ConcurrentHashMap<String, Long> writtenAtMillis = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public AiCache(Duration ttl, long maxSize) {
        Objects.requireNonNull(ttl, "ttl");
        this.ttlMillis = Math.max(1L, ttl.toMillis());
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(Math.max(1L, maxSize))
                .removalListener((key, value, cause) -> {
                    if (key != null) {
                        writtenAtMillis.remove(key);
                    }
                })
                .build();
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        cache.put(key, value);
        writtenAtMillis.put(key, System.currentTimeMillis());
    }

    /**
     * {@code true} when the key is present and younger than 80% of the configured TTL.
     */
    public boolean isFresh(String key) {
        Objects.requireNonNull(key, "key");
        if (cache.getIfPresent(key) == null) {
            return false;
        }
        Long writtenAt = writtenAtMillis.get(key);
        if (writtenAt == null) {
            return false;
        }
        long age = System.currentTimeMillis() - writtenAt;
        return age < (long) (ttlMillis * 0.8d);
    }

    public void invalidateAll() {
        cache.invalidateAll();
        writtenAtMillis.clear();
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
