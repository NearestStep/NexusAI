package io.github.neareststep.nexusai.moderation;

/**
 * Parsed moderator reply. {@code flagged} is false when the model says the message is fine.
 */
public record ModerationVerdict(boolean flagged, String category, String reason) {

    public ModerationVerdict {
        category = category == null || category.isBlank() ? "none" : category;
        reason = reason == null ? "" : reason;
    }

    public static ModerationVerdict clear() {
        return new ModerationVerdict(false, "none", "");
    }
}
