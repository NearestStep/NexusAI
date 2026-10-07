package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import org.bukkit.entity.Player;

/** Called on the thread that owns {@code player}. */
public interface PlayerStateReader {

    PlayerFacts read(Player player, NamedPrompt prompt, GenerationRequest request);
}
