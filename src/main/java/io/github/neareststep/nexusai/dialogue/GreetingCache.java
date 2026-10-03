package io.github.neareststep.nexusai.dialogue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches a generated NPC greeting. Dialogue replies themselves are not cached.
 */
public final class GreetingCache {

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public String get(String key, long nowMillis) {
        if (key == null) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= nowMillis) {
            entries.remove(key, entry);
            return null;
        }
        return entry.text;
    }

    public void put(String key, String text, long nowMillis, long ttlMillis) {
        if (key == null || text == null || text.isBlank() || ttlMillis <= 0) {
            return;
        }
        entries.put(key, new Entry(text, nowMillis + ttlMillis));
    }

    /**
     * SHA-256 of the system text. A 32-bit {@code hashCode} could serve one player's greeting,
     * including their context block, to someone else.
     */
    public static String key(String characterId, String system) {
        String sheet = system == null ? "" : system;
        return (characterId == null ? "" : characterId) + "\u0000" + sha256(sheet);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256", e);
        }
    }

    private record Entry(String text, long expiresAt) {
    }
}
