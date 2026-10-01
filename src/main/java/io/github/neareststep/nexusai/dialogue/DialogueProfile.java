package io.github.neareststep.nexusai.dialogue;

/**
 * Optional per-character overrides from {@code prompts.yml} {@code dialogue:}.
 * A null field inherits {@link DialogueSettings}.
 */
public final class DialogueProfile {

    private static final DialogueProfile ABSENT = new DialogueProfile(false, null, null, null, null, null, null);

    private final boolean defined;
    private final String greeting;
    private final Integer memoryTurns;
    private final Integer sessionTimeoutSeconds;
    private final Integer leaveRadius;
    private final Integer maxReplies;
    private final Integer messageCooldownMillis;

    public DialogueProfile(
            boolean defined,
            String greeting,
            Integer memoryTurns,
            Integer sessionTimeoutSeconds,
            Integer leaveRadius,
            Integer maxReplies,
            Integer messageCooldownMillis
    ) {
        this.defined = defined;
        this.greeting = greeting == null || greeting.isBlank() ? null : greeting;
        this.memoryTurns = memoryTurns;
        this.sessionTimeoutSeconds = sessionTimeoutSeconds;
        this.leaveRadius = leaveRadius;
        this.maxReplies = maxReplies;
        this.messageCooldownMillis = messageCooldownMillis;
    }

    public static DialogueProfile absent() {
        return ABSENT;
    }

    public boolean defined() {
        return defined;
    }

    public String greeting() {
        return greeting;
    }

    public int memoryTurns(int global) {
        return memoryTurns == null ? global : memoryTurns;
    }

    public int sessionTimeoutSeconds(int global) {
        return sessionTimeoutSeconds == null ? global : sessionTimeoutSeconds;
    }

    public int leaveRadius(int global) {
        return leaveRadius == null ? global : leaveRadius;
    }

    public int maxReplies(int global) {
        return maxReplies == null ? global : maxReplies;
    }

    public int messageCooldownMillis(int global) {
        return messageCooldownMillis == null ? global : messageCooldownMillis;
    }
}
