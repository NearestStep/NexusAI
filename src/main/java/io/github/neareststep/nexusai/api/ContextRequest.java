package io.github.neareststep.nexusai.api;

import java.util.UUID;

/**
 * What a context provider may read. There is no {@code Player}: Bukkit state that is only
 * safe on the region thread belongs in the provider's own cache, updated from an event or a
 * sync timer. {@link NexusContextProvider#provide} then returns that cached value.
 */
public record ContextRequest(UUID playerId, String playerName, String world, String promptId, Purpose purpose) {

    public enum Purpose {
        PLACEHOLDER,
        TALK,
        TALK_GREETING
    }

    public ContextRequest {
        playerName = playerName == null ? "" : playerName;
        world = world == null ? "" : world;
        promptId = promptId == null ? "" : promptId;
        if (purpose == null) {
            purpose = Purpose.PLACEHOLDER;
        }
    }
}
