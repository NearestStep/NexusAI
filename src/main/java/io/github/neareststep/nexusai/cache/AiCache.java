package io.github.neareststep.nexusai.cache;

import io.github.neareststep.nexusai.ai.PlayerInput;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * TTL-bounded response cache backed by Caffeine.
 * A put may carry its own TTL; otherwise the cache default is used.
 */
public final class AiCache {

    private final Cache<String, String> cache;
    private final ConcurrentHashMap<String, Long> writtenAtMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ttlNanosByKey = new ConcurrentHashMap<>();
    private final long defaultTtlNanos;

    public AiCache(Duration ttl, long maxSize) {
        Objects.requireNonNull(ttl, "ttl");
        this.defaultTtlNanos = Math.max(1L, ttl.toNanos());
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, String>() {
                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(Math.max(1L, maxSize))
                .removalListener((key, value, cause) -> {
                    if (key != null && cause != RemovalCause.REPLACED) {
                        writtenAtMillis.remove(key);
                        ttlNanosByKey.remove(key);
                    }
                })
                .build();
    }

    public Optional<String> get(String key) {
        String value = cache.getIfPresent(key);
        if (value == null) {
            return Optional.empty();
        }
        String cleaned = PlayerInput.stripSectionSigns(value).trim();
        return cleaned.isEmpty() ? Optional.empty() : Optional.of(cleaned);
    }

    public void put(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        ttlNanosByKey.remove(key);
        write(key, value);
    }

    public void put(String key, String value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");
        ttlNanosByKey.put(key, Math.max(1L, ttl.toNanos()));
        write(key, value);
    }

    private void write(String key, String value) {
        cache.put(key, value);
        writtenAtMillis.put(key, System.currentTimeMillis());
    }

    /**
     * {@code true} when the key is present and younger than 80% of its TTL.
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
        long ttlMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(ttlNanosFor(key)));
        return age < (long) (ttlMillis * 0.8d);
    }

    public void invalidateAll() {
        cache.invalidateAll();
        writtenAtMillis.clear();
        ttlNanosByKey.clear();
    }

    public long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    /**
     * TTL stored for {@code key}, or null when the key is absent.
     * A length-truncated reply uses a shorter value than the cache default.
     */
    public Duration entryTtl(String key) {
        if (key == null || cache.getIfPresent(key) == null) {
            return null;
        }
        return Duration.ofNanos(ttlNanosFor(key));
    }

    private long ttlNanosFor(String key) {
        Long custom = ttlNanosByKey.get(key);
        return custom == null ? defaultTtlNanos : custom;
    }
}
