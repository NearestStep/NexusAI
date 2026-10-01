package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory transcripts with an optional {@code dialogue-memory.yml} snapshot.
 */
public final class MemoryStore {

    private final ConcurrentHashMap<String, TurnMemory> memories = new ConcurrentHashMap<>();

    public TurnMemory get(UUID player, String characterId) {
        return memories.computeIfAbsent(key(player, characterId), ignored -> new TurnMemory());
    }

    public synchronized void append(
            UUID player,
            String characterId,
            String role,
            String text,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis
    ) {
        TurnMemory memory = get(player, characterId);
        memory.expire(nowMillis, expiryMillis);
        memory.add(role, text, nowMillis);
        memory.trim(turns, maxChars);
    }

    public List<TurnMemory.Line> transcript(
            UUID player,
            String characterId,
            long nowMillis,
            int turns,
            int maxChars,
            long expiryMillis
    ) {
        TurnMemory memory = get(player, characterId);
        memory.expire(nowMillis, expiryMillis);
        memory.trim(turns, maxChars);
        return memory.view();
    }

    public void load(File file, long nowMillis, long expiryMillis, Logger logger) {
        if (file == null || !file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("dialogue-memory.yml was not loaded: " + e.getMessage());
            }
            return;
        }
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) {
            return;
        }
        for (String playerKey : entries.getKeys(false)) {
            UUID player;
            try {
                player = UUID.fromString(playerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection characters = entries.getConfigurationSection(playerKey);
            if (characters == null) {
                continue;
            }
            for (String characterId : characters.getKeys(false)) {
                ConfigurationSection one = characters.getConfigurationSection(characterId);
                if (one == null) {
                    continue;
                }
                long updated = one.getLong("updated", 0L);
                if (expiryMillis > 0 && updated > 0 && nowMillis - updated >= expiryMillis) {
                    continue;
                }
                List<TurnMemory.Line> lines = new ArrayList<>();
                for (Object raw : one.getMapList("lines")) {
                    if (!(raw instanceof java.util.Map<?, ?> map)) {
                        continue;
                    }
                    Object role = map.get("role");
                    Object text = map.get("text");
                    if (role == null || text == null) {
                        continue;
                    }
                    String roleText = String.valueOf(role);
                    if (!"user".equals(roleText) && !"assistant".equals(roleText)) {
                        continue;
                    }
                    lines.add(new TurnMemory.Line(roleText, String.valueOf(text)));
                }
                get(player, characterId).load(lines, updated);
            }
        }
    }

    public void save(File file, Logger logger) {
        if (file == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : memories.entrySet()) {
            String[] parts = entry.getKey().split("\u0000", 2);
            if (parts.length != 2) {
                continue;
            }
            TurnMemory memory = entry.getValue();
            List<TurnMemory.Line> lines = memory.view();
            if (lines.isEmpty()) {
                continue;
            }
            String base = "entries." + parts[0] + "." + parts[1];
            yaml.set(base + ".updated", memory.updatedAt());
            List<java.util.Map<String, String>> stored = new ArrayList<>();
            for (TurnMemory.Line line : lines) {
                java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                row.put("role", line.role());
                row.put("text", line.text());
                stored.add(row);
            }
            yaml.set(base + ".lines", stored);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                return;
            }
            yaml.save(file);
        } catch (IOException e) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to save dialogue-memory.yml", e);
            }
        }
    }

    private static String key(UUID player, String characterId) {
        return player + "\u0000" + (characterId == null ? "" : characterId);
    }
}
