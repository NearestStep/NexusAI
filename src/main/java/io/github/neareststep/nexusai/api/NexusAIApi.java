package io.github.neareststep.nexusai.api;

import io.github.neareststep.nexusai.dialogue.DialogueService;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * API for NPC plugins. A blank {@code message} opens a session and completes with the greeting.
 * Any other message is one reply and does not capture later chat.
 * <p>
 * Do not join the returned future on a server region thread. An action command is scheduled back
 * onto that thread, so joining there can stall the call until the action times out.
 * Placeholders never call this API, and this API is the only path that can run character actions.
 */
public final class NexusAIApi {

    private static volatile DialogueService service;

    private NexusAIApi() {
    }

    public static void bind(DialogueService dialogueService) {
        service = dialogueService;
    }

    public static CompletableFuture<String> talk(Player player, String id, String message) {
        DialogueService current = service;
        if (current == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NexusAI is not enabled"));
        }
        if (player == null || id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("player and id are required"));
        }
        return current.talk(player, id, message);
    }
}
