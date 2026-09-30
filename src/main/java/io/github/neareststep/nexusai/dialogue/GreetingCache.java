package io.github.neareststep.nexusai.dialogue;

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

    public static String key(String characterId, String system) {
        String sheet = system == null ? "" : system;
        return (characterId == null ? "" : characterId) + "\u0000" + Integer.toUnsignedString(sheet.hashCode());
    }

    private record Entry(String text, long expiresAt) {
    }
}
