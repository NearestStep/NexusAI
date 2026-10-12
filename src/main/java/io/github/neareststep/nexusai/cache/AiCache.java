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
 * Each entry stores the reply text and, when known, the provider and model that produced it.
 * A put that only passes the text leaves those two fields empty.
 */
public final class AiCache {

    private final Cache<String, CachedAnswer> cache;
    private final ConcurrentHashMap<String, Long> writtenAtMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ttlNanosByKey = new ConcurrentHashMap<>();
    private final long defaultTtlNanos;
    private final boolean allowMarkup;

    public AiCache(Duration ttl, long maxSize) {
        this(ttl, maxSize, false);
    }

    public AiCache(Duration ttl, long maxSize, boolean allowMarkup) {
        Objects.requireNonNull(ttl, "ttl");
        this.allowMarkup = allowMarkup;
        this.defaultTtlNanos = Math.max(1L, ttl.toNanos());
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CachedAnswer>() {
                    @Override
                    public long expireAfterCreate(String key, CachedAnswer value, long currentTime) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedAnswer value, long currentTime, long currentDuration) {
                        return ttlNanosFor(key);
                    }

                    @Override
                    public long expireAfterRead(String key, CachedAnswer value, long currentTime, long currentDuration) {
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
        return lookup(key).map(CachedAnswer::text);
    }

    /**
     * The cached reply, including the provider and model stored with it.
     * A put that did not name them, and a text fallback that was never a model reply, yield empty strings.
     * The text is cleaned the same way as {@link #get(String)}.
     */
    public Optional<CachedAnswer> lookup(String key) {
        CachedAnswer value = cache.getIfPresent(key);
        if (value == null) {
            return Optional.empty();
        }
        String cleaned = PlayerInput.stripSectionSigns(value.text(), allowMarkup).trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CachedAnswer(cleaned, value.providerId(), value.model(), value.note()));
    }

    public void put(String key, String value) {
        put(key, value, "", "");
    }

    public void put(String key, String value, String providerId, String model) {
        put(key, value, providerId, model, "");
    }

    public void put(String key, String value, String providerId, String model, String note) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        ttlNanosByKey.remove(key);
        write(key, new CachedAnswer(value, providerId, model, note));
    }

    public void put(String key, String value, Duration ttl) {
        put(key, value, "", "", ttl);
    }

    public void put(String key, String value, String providerId, String model, Duration ttl) {
        put(key, value, providerId, model, ttl, "");
    }

    public void put(String key, String value, String providerId, String model, Duration ttl, String note) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");
        ttlNanosByKey.put(key, Math.max(1L, ttl.toNanos()));
        write(key, new CachedAnswer(value, providerId, model, note));
    }

    private void write(String key, CachedAnswer value) {
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
     * A put may store a shorter TTL than the cache default.
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

    /**
     * One cached reply. {@code providerId} and {@code model} are empty when the writer did not know them.
     */
    public record CachedAnswer(String text, String providerId, String model, String note) {
        public CachedAnswer(String text, String providerId, String model) {
            this(text, providerId, model, "");
        }

        public CachedAnswer {
            text = text == null ? "" : text;
            providerId = providerId == null ? "" : providerId;
            model = model == null ? "" : model;
            note = note == null ? "" : note;
        }
    }
}
