package io.github.neareststep.nexusai.budget;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reads {@code nexusai.quota.<name>} on the thread that already owns {@code player}.
 * The node is not registered in {@code plugin.yml}. Group names come from config.
 */
public final class QuotaGroups {

    public static final String PERMISSION_PREFIX = "nexusai.quota.";

    private QuotaGroups() {
    }

    public static List<String> held(Player player, Collection<String> groupNames) {
        if (player == null || groupNames == null || groupNames.isEmpty()) {
            return List.of();
        }
        List<String> held = new ArrayList<>();
        for (String name : groupNames) {
            if (name != null && player.hasPermission(PERMISSION_PREFIX + name)) {
                held.add(name);
            }
        }
        return List.copyOf(held);
    }
}
