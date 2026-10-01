package io.github.neareststep.nexusai.dialogue;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Global dialogue and action limits from their own {@code config.yml} sections.
 * Placeholder limits stay under {@code limits}.
 */
public final class DialogueSettings {

    private final boolean dialogueEnabled;
    private final int memoryTurns;
    private final boolean persistMemory;
    private final int memoryMaxChars;
    private final int memoryExpiryHours;
    private final int sessionTimeoutSeconds;
    private final int leaveRadius;
    private final int maxRepliesPerSession;
    private final int messageCooldownMillis;
    private final int conversationsPerPlayerPerDay;
    private final int maxMessageLength;
    private final boolean cacheGreeting;
    private final int greetingCacheSeconds;
    private final boolean actionsEnabled;
    private final boolean actionLog;
    private final int maxActionsPerReply;

    public DialogueSettings(
            boolean dialogueEnabled,
            int memoryTurns,
            boolean persistMemory,
            int memoryMaxChars,
            int memoryExpiryHours,
            int sessionTimeoutSeconds,
            int leaveRadius,
            int maxRepliesPerSession,
            int messageCooldownMillis,
            int conversationsPerPlayerPerDay,
            int maxMessageLength,
            boolean cacheGreeting,
            int greetingCacheSeconds,
            boolean actionsEnabled,
            boolean actionLog,
            int maxActionsPerReply
    ) {
        this.dialogueEnabled = dialogueEnabled;
        this.memoryTurns = memoryTurns;
        this.persistMemory = persistMemory;
        this.memoryMaxChars = memoryMaxChars;
        this.memoryExpiryHours = memoryExpiryHours;
        this.sessionTimeoutSeconds = sessionTimeoutSeconds;
        this.leaveRadius = leaveRadius;
        this.maxRepliesPerSession = maxRepliesPerSession;
        this.messageCooldownMillis = messageCooldownMillis;
        this.conversationsPerPlayerPerDay = conversationsPerPlayerPerDay;
        this.maxMessageLength = maxMessageLength;
        this.cacheGreeting = cacheGreeting;
        this.greetingCacheSeconds = greetingCacheSeconds;
        this.actionsEnabled = actionsEnabled;
        this.actionLog = actionLog;
        this.maxActionsPerReply = maxActionsPerReply;
    }

    public static DialogueSettings defaults() {
        return new DialogueSettings(
                true, 8, false, 8000, 168, 120, 8, 12, 3000, 20, 200, true, 300, true, true, 1);
    }

    public static DialogueSettings read(FileConfiguration config) {
        DialogueSettings base = defaults();
        if (config == null) {
            return base;
        }
        int greetingTtl = Math.max(1, config.getInt("cache.ttl", base.greetingCacheSeconds));
        return new DialogueSettings(
                config.getBoolean("dialogue.enabled", base.dialogueEnabled),
                clamp(config.getInt("dialogue.memory-turns", base.memoryTurns), 1, 16),
                config.getBoolean("dialogue.persist-memory", base.persistMemory),
                clamp(config.getInt("dialogue.memory-max-chars", base.memoryMaxChars), 200, 100_000),
                Math.max(0, config.getInt("dialogue.memory-expiry-hours", base.memoryExpiryHours)),
                clamp(config.getInt("dialogue.session-timeout-seconds", base.sessionTimeoutSeconds), 0, 86_400),
                clamp(config.getInt("dialogue.leave-radius", base.leaveRadius), 0, 10_000),
                clamp(config.getInt("dialogue.max-replies-per-session", base.maxRepliesPerSession), 1, 1_000),
                clamp(config.getInt("dialogue.message-cooldown-millis", base.messageCooldownMillis), 0, 600_000),
                Math.max(0, config.getInt("dialogue.conversations-per-player-per-day", base.conversationsPerPlayerPerDay)),
                clamp(config.getInt("dialogue.max-message-length", base.maxMessageLength), 1, 2_000),
                config.getBoolean("dialogue.cache-greeting", base.cacheGreeting),
                greetingTtl,
                config.getBoolean("actions.enabled", base.actionsEnabled),
                config.getBoolean("actions.log", base.actionLog),
                clamp(config.getInt("actions.max-per-reply", base.maxActionsPerReply), 1, 5)
        );
    }

    public boolean dialogueEnabled() {
        return dialogueEnabled;
    }

    public int memoryTurns() {
        return memoryTurns;
    }

    public boolean persistMemory() {
        return persistMemory;
    }

    public int memoryMaxChars() {
        return memoryMaxChars;
    }

    public int memoryExpiryHours() {
        return memoryExpiryHours;
    }

    public long memoryExpiryMillis() {
        if (memoryExpiryHours <= 0) {
            return 0L;
        }
        return memoryExpiryHours * 3_600_000L;
    }

    public int sessionTimeoutSeconds() {
        return sessionTimeoutSeconds;
    }

    public int leaveRadius() {
        return leaveRadius;
    }

    public int maxRepliesPerSession() {
        return maxRepliesPerSession;
    }

    public int messageCooldownMillis() {
        return messageCooldownMillis;
    }

    public int conversationsPerPlayerPerDay() {
        return conversationsPerPlayerPerDay;
    }

    public int maxMessageLength() {
        return maxMessageLength;
    }

    public boolean cacheGreeting() {
        return cacheGreeting;
    }

    public int greetingCacheSeconds() {
        return greetingCacheSeconds;
    }

    public boolean actionsEnabled() {
        return actionsEnabled;
    }

    public boolean actionLog() {
        return actionLog;
    }

    public int maxActionsPerReply() {
        return maxActionsPerReply;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
