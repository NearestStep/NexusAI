package io.github.neareststep.nexusai.moderation;

/**
 * Tells online staff that a public chat line was flagged.
 * The production notifier schedules that message on the player's region.
 */
public interface StaffNotifier {

    void flagged(String playerName, String message, String category, String reason);
}
