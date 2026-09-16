package io.github.neareststep.nexusai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * TTL-bounded response cache backed by Caffeine.
 */
public final class AiCache {

    private final Cache<String, String> cache;

    public AiCache(Duration ttl, long maxSize) {
        Objects.requireNonNull(ttl, "ttl");
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(Math.max(1L, maxSize))
                .build();
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        cache.put(key, value);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
