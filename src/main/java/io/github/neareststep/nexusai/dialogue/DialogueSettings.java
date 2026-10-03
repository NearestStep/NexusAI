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
    private final boolean summaryEnabled;
    private final int summaryThresholdTurns;
    private final int summaryMaxChars;
    private final int summaryMaxTokens;
    private final String summaryProvider;
    private final String summaryModel;

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
        this(
                dialogueEnabled,
                memoryTurns,
                persistMemory,
                memoryMaxChars,
                memoryExpiryHours,
                sessionTimeoutSeconds,
                leaveRadius,
                maxRepliesPerSession,
                messageCooldownMillis,
                conversationsPerPlayerPerDay,
                maxMessageLength,
                cacheGreeting,
                greetingCacheSeconds,
                actionsEnabled,
                actionLog,
                maxActionsPerReply,
                false,
                2,
                400,
                200,
                "",
                ""
        );
    }

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
            int maxActionsPerReply,
            boolean summaryEnabled,
            int summaryThresholdTurns,
            int summaryMaxChars,
            int summaryMaxTokens,
            String summaryProvider,
            String summaryModel
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
        this.summaryEnabled = summaryEnabled;
        this.summaryThresholdTurns = summaryThresholdTurns;
        this.summaryMaxChars = summaryMaxChars;
        this.summaryMaxTokens = summaryMaxTokens;
        this.summaryProvider = summaryProvider == null ? "" : summaryProvider;
        this.summaryModel = summaryModel == null ? "" : summaryModel;
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
        String summaryProvider = config.getString("dialogue.summary.provider", "");
        String summaryModel = config.getString("dialogue.summary.model", "");
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
                clamp(config.getInt("actions.max-per-reply", base.maxActionsPerReply), 1, 5),
                config.getBoolean("dialogue.summary.enabled", false),
                clamp(config.getInt("dialogue.summary.threshold-turns", 2), 1, 16),
                clamp(config.getInt("dialogue.summary.max-chars", 400), 100, 2_000),
                config.getInt("dialogue.summary.max-tokens", 200),
                summaryProvider == null ? "" : summaryProvider.trim().toLowerCase(java.util.Locale.ROOT),
                summaryModel == null ? "" : summaryModel.trim()
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

    public boolean summaryEnabled() {
        return summaryEnabled;
    }

    public int summaryThresholdTurns() {
        return summaryThresholdTurns;
    }

    public int summaryMaxChars() {
        return summaryMaxChars;
    }

    /**
     * Token cap sent only on the summary call. {@code <= 0} omits {@code max_tokens}.
     */
    public int summaryMaxTokens() {
        return summaryMaxTokens;
    }

    public String summaryProvider() {
        return summaryProvider;
    }

    public String summaryModel() {
        return summaryModel;
    }

    /**
     * Both provider and model are required to pin the summary call.
     * Either one alone still uses the model queue, the same rule as moderation.
     */
    public boolean summaryPinned() {
        return !summaryProvider.isBlank() && !summaryModel.isBlank();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
