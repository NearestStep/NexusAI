package io.github.neareststep.nexusai.cache;

import io.github.neareststep.nexusai.ai.PlayerInput;
import io.github.neareststep.nexusai.text.Utf16;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.nio.charset.StandardCharsets;
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
 * When {@code enabled} is false, reads miss and writes are ignored.
 * When {@code maxMemoryBytes} is greater than zero, eviction uses estimated UTF-8 weight
 * instead of entry count ({@code maxSize}).
 */
public final class AiCache {

    private static final int ENTRY_OVERHEAD_BYTES = 64;

    private final Cache<String, CachedAnswer> cache;
    private final ConcurrentHashMap<String, Long> writtenAtMillis = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ttlNanosByKey = new ConcurrentHashMap<>();
    private final long defaultTtlNanos;
    private final boolean allowMarkup;
    private final boolean enabled;
    private final long maxMemoryBytes;

    public AiCache(Duration ttl, long maxSize) {
        this(ttl, maxSize, false);
    }

    public AiCache(Duration ttl, long maxSize, boolean allowMarkup) {
        this(ttl, maxSize, allowMarkup, true, 0L);
    }

    public AiCache(Duration ttl, long maxSize, boolean allowMarkup, boolean enabled, long maxMemoryBytes) {
        Objects.requireNonNull(ttl, "ttl");
        this.allowMarkup = allowMarkup;
        this.enabled = enabled;
        this.maxMemoryBytes = Math.max(0L, maxMemoryBytes);
        this.defaultTtlNanos = Math.max(1L, ttl.toNanos());
        Caffeine<String, CachedAnswer> builder = Caffeine.newBuilder()
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
                .removalListener((key, value, cause) -> {
                    if (key != null && cause != RemovalCause.REPLACED) {
                        writtenAtMillis.remove(key);
                        ttlNanosByKey.remove(key);
                    }
                });
        if (this.maxMemoryBytes > 0L) {
            builder.maximumWeight(this.maxMemoryBytes)
                    .weigher(AiCache::weightOf);
        } else {
            builder.maximumSize(Math.max(1L, maxSize));
        }
        this.cache = builder.build();
    }

    public boolean enabled() {
        return enabled;
    }

    public long maxMemoryBytes() {
        return maxMemoryBytes;
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
        if (!enabled) {
            return Optional.empty();
        }
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
        if (!enabled) {
            return;
        }
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
        if (!enabled) {
            return;
        }
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
        if (!enabled || cache.getIfPresent(key) == null) {
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
        if (!enabled || key == null || cache.getIfPresent(key) == null) {
            return null;
        }
        return Duration.ofNanos(ttlNanosFor(key));
    }

    /**
     * Estimated UTF-8 weight of one entry, including a fixed object overhead.
     */
    public static int weightOf(String key, CachedAnswer value) {
        long bytes = utf8(key)
                + utf8(value == null ? null : value.text())
                + utf8(value == null ? null : value.providerId())
                + utf8(value == null ? null : value.model())
                + utf8(value == null ? null : value.note())
                + ENTRY_OVERHEAD_BYTES;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, bytes));
    }

    private static int utf8(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private long ttlNanosFor(String key) {
        Long custom = ttlNanosByKey.get(key);
        return custom == null ? defaultTtlNanos : custom;
    }

    /**
     * One cached reply. {@code providerId} and {@code model} are empty when the writer did not know them.
     * A lone UTF-16 surrogate in {@code text} is stored as U+FFFD.
     */
    public record CachedAnswer(String text, String providerId, String model, String note) {
        public CachedAnswer(String text, String providerId, String model) {
            this(text, providerId, model, "");
        }

        public CachedAnswer {
            text = text == null ? "" : Utf16.replaceLoneSurrogates(text);
            providerId = providerId == null ? "" : providerId;
            model = model == null ? "" : model;
            note = note == null ? "" : note;
        }
    }
}
