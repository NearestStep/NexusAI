package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.context.ContextVariables;
import io.github.neareststep.nexusai.placeholder.VarSubstitutor;
import io.github.neareststep.nexusai.prompt.NamedPrompt;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads built-ins and resolves {@code %} in prompt variables. Request variables are not resolved. */
public final class BukkitPlayerState implements PlayerStateReader {

    @Override
    public PlayerFacts read(Player player, NamedPrompt prompt, GenerationRequest request) {
        if (player == null || !player.isOnline()) {
            return PlayerFacts.unavailable();
        }
        Map<String, String> builtins = ContextVariables.capture(player);
        Map<String, String> resolved = new LinkedHashMap<>();
        if (prompt != null && request != null) {
            for (Map.Entry<String, String> entry : prompt.vars().entrySet()) {
                if (request.vars().containsKey(entry.getKey())) {
                    continue;
                }
                String value = entry.getValue();
                if (value != null && value.indexOf('%') >= 0) {
                    String substituted = VarSubstitutor.resolve(player, value);
                    resolved.put(entry.getKey(), substituted == null ? "" : substituted);
                }
            }
        }
        return PlayerFacts.available(builtins, resolved, name(player), world(player));
    }

    private static String name(Player player) {
        try {
            String value = player.getName();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String world(Player player) {
        try {
            World world = player.getWorld();
            if (world == null || world.getName() == null) {
                return "";
            }
            return world.getName();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
