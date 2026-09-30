package io.github.neareststep.nexusai.dialogue;

/**
 * Why {@link DialogueEngine} stopped or what it wants the player to see.
 */
public enum TalkCode {
    DISABLED,
    UNKNOWN,
    NO_SESSION,
    ENDED,
    STARTED,
    COOLDOWN,
    TOO_LONG,
    REPLIES,
    DAILY,
    BUSY,
    FAILED,
    REPLY,
    EMPTY
}
