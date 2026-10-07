package io.github.neareststep.nexusai.generate;

import java.util.Map;

/**
 * One region-thread snapshot. Built-in values are raw. Prompt variables that contained
 * {@code %} are already resolved and not yet wrapped.
 */
public final class PlayerFacts {

    private static final PlayerFacts NONE = new PlayerFacts(true, Map.of(), Map.of(), "", "");
    private static final PlayerFacts UNAVAILABLE = new PlayerFacts(false, Map.of(), Map.of(), "", "");

    private final boolean available;
    private final Map<String, String> builtins;
    private final Map<String, String> resolvedPromptVars;
    private final String playerName;
    private final String worldName;

    private PlayerFacts(
            boolean available,
            Map<String, String> builtins,
            Map<String, String> resolvedPromptVars,
            String playerName,
            String worldName
    ) {
        this.available = available;
        this.builtins = builtins == null || builtins.isEmpty() ? Map.of() : Map.copyOf(builtins);
        this.resolvedPromptVars = resolvedPromptVars == null || resolvedPromptVars.isEmpty()
                ? Map.of()
                : Map.copyOf(resolvedPromptVars);
        this.playerName = playerName == null ? "" : playerName;
        this.worldName = worldName == null ? "" : worldName;
    }

    /** No player read. Built-in tokens stay in the template. */
    public static PlayerFacts none() {
        return NONE;
    }

    public static PlayerFacts unavailable() {
        return UNAVAILABLE;
    }

    public static PlayerFacts available(
            Map<String, String> builtins,
            Map<String, String> resolvedPromptVars,
            String playerName,
            String worldName
    ) {
        return new PlayerFacts(true, builtins, resolvedPromptVars, playerName, worldName);
    }

    public boolean available() {
        return available;
    }

    public Map<String, String> builtins() {
        return builtins;
    }

    public Map<String, String> resolvedPromptVars() {
        return resolvedPromptVars;
    }

    public String playerName() {
        return playerName;
    }

    public String worldName() {
        return worldName;
    }
}
