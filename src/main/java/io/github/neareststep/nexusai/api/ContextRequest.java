package io.github.neareststep.nexusai.api;

import java.util.UUID;

/**
 * What a context provider may read. There is no {@code Player}: Bukkit state that is only
 * safe on the region thread belongs in the provider's own cache, updated from an event or
 * {@code Bukkit.getGlobalRegionScheduler()}. On Paper that scheduler is the main thread. On Folia,
 * read each player on the region owner's thread. {@link NexusContextProvider#provide} then returns
 * that cached value.
 */
public record ContextRequest(UUID playerId, String playerName, String world, String promptId, Purpose purpose) {

    public enum Purpose {
        /**
         * A placeholder resolution. A player {@code /nai test} of a named prompt is also this
         * purpose. There is no separate test value.
         */
        PLACEHOLDER,
        /** A player line in {@code /nai talk}. */
        TALK,
        /** The opening line of a talk session. */
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
